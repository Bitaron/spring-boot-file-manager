# REST API resource design

## Route structure

Folder/File listings are **flat collections with a query param**, not nested paths that mirror the hierarchy: `GET /folders?parentId={id}` (omit `parentId` for top-level Folders) and `GET /folders/{id}/files` for the Files directly inside one Folder. Folder nesting can be arbitrarily deep (adjacency list, decision #18/ADR 0003), so a URL that grows with depth doesn't fit; a Drive-like client always fetches "children of X" as one flat call regardless of how deep X sits. Files are the one exception allowed a nested path — a File always belongs to exactly one Folder (`parent_folder_id NOT NULL`), so that nesting is only ever one level deep and never recurses.

Full route list (all under `/api/v1`, ApiKey-authenticated per `docs/api-design.md`):

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/folders?parentId=` | List a Folder's child Folders (omit param for top-level) |
| `GET` | `/folders/{id}` | Folder metadata |
| `POST` | `/folders` | Create Folder — body `{name, parentFolderId?}` |
| `PATCH` | `/folders/{id}` | Rename/move — body `{name?, parentFolderId?}` |
| `POST` | `/folders/{id}/trash` | Trash (cascades to descendants via computed state, decision #16) |
| `POST` | `/folders/{id}/restore` | Restore (independent per-item, decision #16) |
| `DELETE` | `/folders/{id}` | Purge — cascades to descendant metadata + StorageBackend content |
| `GET` | `/folders/{id}/files` | List Files directly inside a Folder |
| `GET` | `/files/{id}` | File metadata |
| `GET` | `/files/{id}/content?disposition=view\|download` | Secure Access download of the File's bytes (default `download`) |
| `POST` | `/files` | Upload — `multipart/form-data`, `file` part + `metadata` JSON part (`{folderId, name, visibility}`) |
| `PATCH` | `/files/{id}` | Rename/move — body `{name?, parentFolderId?}` |
| `POST` | `/files/{id}/trash` | Trash |
| `POST` | `/files/{id}/restore` | Restore |
| `DELETE` | `/files/{id}` | Purge — deletes metadata + StorageBackend content |
| `POST` | `/files/{id}/access-tokens` | Mint an AccessToken — body `{purpose: VIEW\|DOWNLOAD, ttlSeconds?}`, response `{token, expiresAt, url}` |
| `GET` | `/trash?parentId=` | Unified trash listing (Files + Folders, discriminated by a `type` field); omit `parentId` for tenant-wide trash |

`GET /access/{token}` (Non-secure Access, decision #14) is unauthenticated and intentionally outside `/api/v1` — it is not part of the Secure Access surface this route table covers.

## Trash and move as actions, not field toggles

Rename/move (`PATCH`) and trash/restore/purge are deliberately different HTTP verbs for what could both be phrased as "update a field." Rename/move really is "update one field of this resource" — the textbook `PATCH` case, no side effects beyond the row itself. Trash/restore/purge are workflows with side effects beyond a field write: trashing a Folder cascades to descendants via computed/inherited state (decision #16) and purging cascades to actually deleting StorageBackend content — both are exactly what a dedicated action endpoint is for, and PATCH-of-a-`trashed` field would misrepresent that. `DELETE` reads naturally as purge given Trash already softened the meaning of "delete" for this domain.

## Trash listing

`GET /trash?parentId=` returns a unified, discriminated-union list of trashed Files and Folders rather than two separate listings — matching how a Drive-like trash UI is actually browsed, as one bin, not two. `parentId` scopes it to "trash within this folder" vs. omitted for "everything I've trashed," Drive's two conventional trash views.

Ordinary (non-trash) listing endpoints — `GET /folders?parentId=`, `GET /folders/{id}/files` — silently exclude trashed items with no filter flag to opt back in; trashed items are "removed from normal listings" by definition (see Trash in `CONTEXT.md`), and `GET /trash` already exists as the one place to see them.

## AccessToken minting response

`POST /files/{id}/access-tokens` returns `{token, expiresAt, url}`, including the constructed relative redemption URL (`/access/{token}`) rather than just the bare token. The token alone would force every caller to know and reconstruct the convention client-side; returning the ready-to-use URL removes a whole class of client bugs (wrong path, missing leading slash, absolute-vs-relative confusion) for a one-line server-side addition.

## Pagination

Cursor-based, not offset/limit, over the UUIDv7 PK — which is already time-ordered (decision #18/ADR 0003), so no separate `ORDER BY created_at` is needed; the PK ordering *is* creation order. Offset/limit degrades under concurrent inserts/deletes (duplicate or skipped rows across pages); cursor pagination is a small lift given the PK is already sortable. Default page size 50, max 200.

## Error responses

RFC 7807 `application/problem+json` via Spring's built-in `ProblemDetail` (available in the confirmed Spring Boot 4.1.1 baseline, decision #11) — not a custom envelope. It's a standard shape springdoc documents automatically, with no bespoke error format to hand-design or hand-document.

## HTTP status codes

- `POST /folders`, `POST /files` (create) → `201 Created` with a `Location` header pointing at the new resource.
- `POST .../trash`, `.../restore`, `.../access-tokens` (actions on an existing resource, not creation of a new URI) → `200 OK` with the updated/minted resource body.
- `DELETE /files/{id}`, `/folders/{id}` (purge) → `204 No Content`.
- Missing/malformed/revoked ApiKey → `401`.

**Deliberately non-standard, to preserve non-enumerability:** a *valid* ApiKey from Tenant A requesting a resource belonging to Tenant B, and a request for a resource that doesn't exist at all, both return the same `404` — never `403`, which would confirm the resource exists but belongs to someone else. Likewise `GET /access/{token}` returns the same `404` whether the token is expired or never existed at all — not a distinct `410 Gone` for expired — since a distinguishable code would let an attacker learn a guessed token string was once valid, undermining decision #14's non-enumerability intent. This is the one place a "conventional" REST choice (`403` for authz, `410` for expired) would actively work against decisions already made on this map, so it's called out explicitly rather than left to implementation-time guesswork.

## DTO field exposure

Response DTOs mirror entity fields closely (`id`, `name`, `size`, `contentType`, `visibility`, timestamps, `parentFolderId`, ...) with one deliberate omission: `storageReference` never appears in any File response body. It's an internal handle core passes to StorageBackend (decision #13) with no meaning to a client, and exposing it would leak the storage backend's internal addressing scheme (a local path, an S3 key) for zero client benefit.

## Considered options

- **Nested paths mirroring the Folder tree** (`/folders/{a}/folders/{b}/files`) — rejected: grows with hierarchy depth, which can be arbitrary; doesn't match the adjacency-list model or how a Drive-like client actually fetches "children of X."
- **Flat form fields for upload metadata** (`folderId`, `name`, `visibility` as separate multipart form parts) instead of a JSON `metadata` part — rejected: gives up type validation and OpenAPI schema generation on the metadata half for no benefit.
- **`trashed`/`parentFolderId` toggle via the same `PATCH`** used for rename/move — rejected: misrepresents Trash's cascading, side-effecting semantics (decision #16) as a plain field write.
- **Two separate trash listings** (Files, Folders) instead of one unified `GET /trash` — rejected: doesn't match how a Drive-like trash UI is browsed, as one bin.
- **Offset/limit pagination** — rejected: degrades under concurrent inserts/deletes; cursor pagination over the already-time-ordered UUIDv7 PK is barely more work.
- **Custom error envelope** instead of RFC 7807 — rejected: no reason to hand-design a shape when Spring Boot 4.1.1 ships `ProblemDetail` support and springdoc documents it automatically.
- **`403` for cross-tenant access, `410 Gone` for expired AccessTokens** — rejected: both leak information (resource existence; token-was-once-valid) that decisions #14 and #18 deliberately designed the id/token schemes to not leak.
