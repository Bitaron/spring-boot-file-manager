# Architecture

## Maven multi-module layout

Suggested reactor structure — one module per deployable/consumable unit, so Embedded Mode consumers depend only on the engine, never on the standalone service:

```
file-manager/
├── pom.xml                      # parent reactor
├── file-manager-core/           # domain model + engine: Tenant, Folder, File,
│                                 #   Visibility, AccessToken, Trash — no Spring Web
├── file-manager-storage-api/    # StorageBackend interface, no implementation
├── file-manager-storage-local/  # local-disk StorageBackend implementation
├── file-manager-storage-s3/     # S3-compatible StorageBackend implementation
├── file-manager-spring-boot-starter/  # autoconfigure for Embedded Mode
├── file-manager-service/        # Standalone Service: REST API + Swagger, depends
│                                 #   on core + starter
├── file-manager-test-support/   # shared fixtures/builders for unit + integration tests
└── file-manager-usage-example/  # runnable Embedded Mode reference app (issue #66) - a leaf,
                                  #   depended on by nothing, depending on nothing but the starter
```

Add a new `file-manager-storage-*` module for each new backend rather than branching inside one module — this is what keeps StorageBackend swappable.

## Embedded Mode vs Standalone Service

Both forms embed the same `file-manager-core` engine — see [Embedded Mode](../CONTEXT.md) and [Standalone Service](../CONTEXT.md) in the domain glossary. The engine itself never opens a socket or owns a database connection pool; it is handed a `DataSource` and a `StorageBackend` by whichever consumer wires it (the starter's autoconfigure, or the standalone service's own config). Multiple applications embedding the engine can share one Tenant's file space purely by pointing at the same database and StorageBackend — don't introduce any in-process cache or lock that would break that sharing.

## StorageBackend contract

`file-manager-storage-api` defines the interface; it must stay free of any specific backend's SDK types. A new backend module implements the interface and nothing else — no core or service code should import a storage SDK directly.

## Deep-module boundaries

Each module's public surface should be small relative to what it does:

- `file-manager-core` exposes the domain operations (create/move/trash/purge Folders and Files, mint AccessTokens) behind service interfaces — not entity setters.
- `file-manager-storage-api` exposes one interface (StorageBackend) — implementations are swapped by Spring configuration, never by caller branching.
- `file-manager-service`'s controllers are a thin translation layer onto `file-manager-core`'s services — no business rules live in a controller.

Keep the structure extensible toward a fuller Drive-like feature set (sharing, search, versioning) without a v1 rewrite: favor adding a new module or a new method on an existing interface over widening an entity's public API.
