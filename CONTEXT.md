# File Manager

A multi-tenant file-management service, usable as an embeddable library or a standalone Spring Boot service, providing Drive-like hierarchical file storage with swappable storage backends.

## Language

**Tenant**:
The top-level isolation boundary. Owns a set of Folders, Files, and ApiKeys; all data belongs to exactly one Tenant.

**ApiKey**:
A credential scoped to exactly one Tenant, used to make Secure Access calls. A Tenant may hold many ApiKeys.

**Folder**:
A hierarchical container owned by a Tenant, holding Files and child Folders. Always private — Folder-level public visibility is out of scope for v1.
_Avoid_: Directory

**File**:
A stored object with binary content and metadata, belonging to exactly one Folder and one Tenant. Has a Visibility of Public or Private.
_Avoid_: Object, Blob, Document

**Visibility**:
A per-File attribute, `Public` or `Private`, that determines which access path applies to the File's content. Set at the File level only; Folders have no visibility of their own in v1.

**Secure Access**:
Fetching a File's content via an authenticated (ApiKey) call. The only access path for a `Private` File.
_Avoid_: authenticated access, private access

**Non-secure Access**:
Fetching a `Public` File's content via a short-lived, unauthenticated URL bearing an AccessToken. The only access path for a `Public` File — a Public File is never fetched via a Secure Access call.
_Avoid_: public access, anonymous access

**AccessToken**:
A short-lived, non-enumerable credential minted (via an authenticated request) for a specific `Public` File, scoped to one Purpose (`View` or `Download`) and carrying its own expiry. Distinct from the File's own identifier — an AccessToken can lapse without affecting the File.
_Avoid_: share link, share token (implies persistence; these are ephemeral)

**Purpose** (of an AccessToken):
`View` or `Download` — the two kinds of Non-secure Access. Each is requested and expires independently.

**Trash**:
The soft-deleted state of a File or Folder: removed from normal listings and hierarchy traversal but recoverable until purged. Distinct from permanent deletion.
_Avoid_: recycle bin, deleted

**StorageBackend**:
The pluggable implementation responsible for persisting and retrieving a File's binary content, decoupled from the File's identity and metadata. Swappable per deployment (e.g. local disk, S3-compatible).
_Avoid_: StorageProvider, storage adapter

**Actor**:
The identity recorded as having performed an action (create/update/delete/trash/purge a File or Folder, mint an AccessToken). In v1 every Actor is an ApiKey; the reference is kept as an opaque id, not a construct exclusive to ApiKey, so a future Actor kind (e.g. a human User) can be added without migrating existing records.

**Embedded Mode**:
The library form of the service: the core engine runs in-process inside the consuming application via the Spring Boot autoconfigure/starter, reading and writing the consuming application's own configured database and StorageBackend. The source of truth (metadata + storage) is external to the engine's code, so multiple applications embedding it can share one Tenant's file space by pointing at the same database and StorageBackend.
_Avoid_: library mode (ambiguous — see also Standalone Service)

**Standalone Service**:
The deployable form of the service: a Spring Boot application that embeds the same core engine (via Embedded Mode) and exposes it over a REST API, for consumers that don't embed the Java library directly.
_Avoid_: server mode
