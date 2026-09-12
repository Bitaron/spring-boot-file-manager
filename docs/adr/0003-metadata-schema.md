# Metadata schema: entity fields for Tenant/Folder/File/ApiKey/AccessToken

`Tenant`, `Folder`, `File`, and `ApiKey` all use a single **UUIDv7** column as their primary key, used directly as the id exposed via the REST API — no separate sequential internal id and public id pair. `tenant_id`-scoped queries (decision #4) stop cross-tenant *access*, but a sequential PK would still leak cross-tenant *volume/order* information (row counts, creation order) to any authenticated caller; a random surrogate closes that gap. UUIDv7 was chosen over UUIDv4 because its time-ordering avoids the b-tree index fragmentation a fully random PK causes, at no extra implementation cost over v4. `AccessToken` is the one exception: its `token` column (already an opaque, unique, random string per decision #14, and exactly what `GET /access/{token}` looks up by) *is* the primary key — a UUIDv7 surrogate alongside it would be pure redundancy.

Folder hierarchy is a plain **adjacency list** — a nullable, self-referencing `parent_folder_id` on `Folder` (`NULL` = top-level; a Tenant may have any number of top-level Folders, no auto-created root). Ancestor-trashed checks (decision #16's "own flag or any ancestor's") and breadcrumb navigation walk the parent chain at read time. This was chosen over a materialized path specifically because a path column has to be rewritten on every descendant when a Folder moves — a bulk write decision #16 deliberately avoided for trash state, and the adjacency list keeps a move to a single-row `UPDATE`.

Actor reference columns (`created_by`, `last_modified_by`, `trashed_by` on `Folder`/`File`; `minted_by` on `AccessToken`) are plain `UUID` columns with **no database-level foreign key** to `ApiKey`, enforced at the application layer only. Decision #10 requires the Actor reference to stay opaque so a future non-ApiKey Actor (e.g. a human `User`) can populate the same column without a schema migration; a DB-level FK to `api_key(id)` would foreclose that by construction. These columns record only the *current* actor per state transition (last created/modified/trashed/minted-by), mirroring decision #16's choice of computed row state over an event log — there is no separate audit-history table in v1. A full action history is a natural v2 add (the sibling `spring-boot-activity-log` project could slot in as-is) but nothing on this map scopes it for v1.

Neither `File` nor `Folder` enforces a uniqueness constraint on `(tenant_id, parent_folder_id, name)` — duplicate sibling names are allowed side by side, matching Drive-like behavior (Google Drive permits this) and avoiding collision-handling logic in create/rename/move that nothing on this map asked for.

## Entity fields

**Tenant**
- `id UUID` (v7) — PK
- `name VARCHAR NOT NULL`
- `created_at TIMESTAMP NOT NULL`

**Folder**
- `id UUID` (v7) — PK
- `tenant_id UUID NOT NULL` — FK → `tenant(id)`, indexed
- `parent_folder_id UUID NULL` — FK → `folder(id)` (self), indexed; `NULL` = top-level. Composite index `(tenant_id, parent_folder_id)` serves "list children" queries.
- `name VARCHAR NOT NULL` — no uniqueness constraint among siblings
- `created_at TIMESTAMP NOT NULL`, `created_by UUID NOT NULL` (opaque Actor ref, no DB FK)
- `updated_at TIMESTAMP NOT NULL`, `last_modified_by UUID NOT NULL` (opaque Actor ref)
- `trashed_at TIMESTAMP NULL`, `trashed_by UUID NULL` (opaque Actor ref) — own-flag half of decision #16's computed trash state

**File**
- `id UUID` (v7) — PK
- `tenant_id UUID NOT NULL` — FK → `tenant(id)`, indexed
- `parent_folder_id UUID NOT NULL` — FK → `folder(id)`, indexed (every File belongs to exactly one Folder, per `CONTEXT.md`; composite index `(tenant_id, parent_folder_id)` as above)
- `name VARCHAR NOT NULL` — no uniqueness constraint among siblings
- `size BIGINT NOT NULL` — bytes
- `content_type VARCHAR NOT NULL`
- `visibility VARCHAR NOT NULL DEFAULT 'PRIVATE'` — `PUBLIC` \| `PRIVATE`
- `storage_reference VARCHAR NOT NULL` — the opaque `String` returned by `StorageBackend.store` (decision #13); core never interprets it
- `created_at TIMESTAMP NOT NULL`, `created_by UUID NOT NULL` (opaque Actor ref)
- `updated_at TIMESTAMP NOT NULL`, `last_modified_by UUID NOT NULL` (opaque Actor ref)
- `trashed_at TIMESTAMP NULL`, `trashed_by UUID NULL` (opaque Actor ref) — indexed, for trash-listing queries

**ApiKey**
- `id UUID` (v7) — PK
- `tenant_id UUID NOT NULL` — FK → `tenant(id)`, indexed
- `label VARCHAR NULL` — human-readable, for operators distinguishing keys at provisioning/revocation time (decisions #6, #15)
- `key_hash CHAR(64) NOT NULL` — SHA-256 hex digest, unique indexed (decision #15)
- `created_at TIMESTAMP NOT NULL`
- `revoked_at TIMESTAMP NULL` — decision #15

**AccessToken**
- `token VARCHAR NOT NULL` — PK; the opaque random token itself (decision #14), not a surrogate id
- `tenant_id UUID NOT NULL` — FK → `tenant(id)`, indexed
- `file_id UUID NOT NULL` — FK → `file(id)`, indexed
- `purpose VARCHAR NOT NULL` — `VIEW` \| `DOWNLOAD` (decision #14)
- `expires_at TIMESTAMP NOT NULL` — indexed
- `minted_at TIMESTAMP NOT NULL`, `minted_by UUID NOT NULL` (opaque Actor ref)

## Considered options

- **Sequential bigint PK + separate opaque `public_id` column** — rejected: doubles id-handling logic (translate `public_id` → internal id on every read path) to buy join/index locality that UUIDv7 already delivers without a second column.
- **UUIDv4 PK** — rejected: same implementation simplicity as UUIDv7, but random (non-time-ordered) UUIDs fragment b-tree indexes as a table grows; v7 avoids this for free.
- **Materialized path for Folder hierarchy** — rejected: requires rewriting every descendant's path on a Folder move, a bulk write decision #16 deliberately avoided for trash-state computation; the adjacency list keeps moves to a single-row update.
- **Separate `AuditEvent`/activity-log table for actor tracking** — rejected: no ticket on this map scopes a full audit-history feature for v1. Per-row "last actor" columns satisfy decision #10's traceability requirement at far lower cost; `spring-boot-activity-log` remains available to slot in later if full history becomes a real v2 requirement.
- **Enforcing unique `(tenant_id, parent_folder_id, name)`** — rejected: pushes collision-handling (409s, dedup-naming) into every create/rename/move flow that nothing on this map asked for; Drive itself doesn't enforce this.
- **One implicit root Folder auto-created per Tenant** — rejected: needs a bootstrap step in decision #6's seed script and a special Folder that can't be renamed/trashed/moved like an ordinary one. A nullable `parent_folder_id` already expresses "top-level" without the special case.
