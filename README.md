# File Manager

A multi-tenant, Drive-like hierarchical file-management engine — hierarchical Folders, File metadata + binary content, per-file `Public`/`Private` visibility, reversible Trash, and swappable storage backends (local filesystem or S3-compatible, including MinIO).

Use it two ways from one codebase:

- **Embedded Mode** — add a Spring Boot starter and call the engine in-process, sharing your own app's `DataSource` and `StorageBackend`.
- **Standalone Service** — run it as its own deployable Spring Boot app behind a versioned, Swagger-documented REST API.

See [`CONTEXT.md`](CONTEXT.md) for the domain glossary (Tenant, Folder, File, Visibility, Secure/Non-secure Access, Trash, etc.) and [`docs/architecture.md`](docs/architecture.md) for the module layout.

## Modules

| Module | Purpose |
|---|---|
| `file-manager-core` | Domain model + engine (Tenant, Folder, File, Trash, AccessToken). No Spring Web dependency. |
| `file-manager-storage-api` | The `StorageBackend` interface only. |
| `file-manager-storage-local` | Local-filesystem `StorageBackend` implementation. |
| `file-manager-storage-s3` | S3-compatible `StorageBackend` implementation (AWS S3 or MinIO). |
| `file-manager-spring-boot-autoconfigure` | Embedded Mode wiring. |
| `file-manager-spring-boot-starter` | The one dependency a host app adds for Embedded Mode. |
| `file-manager-api` | REST request/response DTOs. |
| `file-manager-service` | The Standalone Service: REST + Swagger, deployable on its own. |
| `file-manager-test-support` | Shared test fixtures and the StorageBackend contract-test suite. |

## Installing from Maven

`groupId`: `io.github.bitaron` · current version: `0.1.0-SNAPSHOT`

### From the internal Nexus

<!-- TODO: fill in the real Nexus repository id/URL once distributionManagement is configured. -->

```xml
<repositories>
    <repository>
        <id>internal-nexus</id>
        <url>https://nexus.example.internal/repository/maven-releases/</url>
    </repository>
</repositories>
```

Then depend on the starter (Embedded Mode) or add other modules as needed:

```xml
<dependency>
    <groupId>io.github.bitaron</groupId>
    <artifactId>file-manager-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### From the public Maven repository

<!-- TODO: placeholder — fill in once published outside the internal registry. -->

```xml
<!-- Not yet published publicly. -->
```

## Embedded Mode

Add the starter, supply a `DataSource` bean, and call the granular per-aggregate services (`FolderService`, `FileService`, `TrashService`, `AccessTokenService`) directly — no HTTP layer, no separate persistence unit:

```java
folderService.create(tenantId, actor, "Quarterly Reports", null);
FolderService.listChildren(tenantId, null);
```

Full walkthrough — required beans, what the autoconfiguration wires up automatically, `ApiKeyResolver` for embedding ApiKey-based auth, Trash/restore — is in [`docs/library-usage.md`](docs/library-usage.md).

## Standalone Service

Run it as its own app:

```bash
mvn install
mvn -pl file-manager-service spring-boot:run \
  -Dspring-boot.run.arguments="--spring.datasource.url=<your-jdbc-url> --spring.datasource.username=<user> --spring.datasource.password=<pass>"
```

It listens on port `8080` by default and exposes the REST API under `/api/v1`, authenticated via an `ApiKey` (`Authorization: Bearer fm_<secret>`). See [`docs/api-design.md`](docs/api-design.md) for the full route table, request/response shapes, and error format (RFC 7807 `application/problem+json`).

### Swagger / OpenAPI

Once the service is running:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Raw OpenAPI JSON: `http://localhost:8080/v3/api-docs`

Every `/api/v1` endpoint is documented there, including the `ApiKey` bearer security scheme. The one unauthenticated route, `GET /access/{token}` (AccessToken redemption for Public Files), is documented too but needs no credential to call.

## Configuration

None of these have `application.yml` defaults baked into the shipped jars — set them via your own `application.yml`, environment variables, or `-D` system properties.

| Property | Required | Default | Meaning |
|---|---|---|---|
| `spring.datasource.*` | Yes (Standalone Service) | — | Standard Spring Boot `DataSource` config. Embedded Mode reuses whatever `DataSource` bean your host app already exposes. |
| `file-manager.storage.backend` | Yes | — | `local` or `s3` — selects which `StorageBackend` autoconfiguration activates. Only the backend module(s) on your classpath matter; the other stays inert. |
| `file-manager.storage.local.root-directory` | Yes, if `backend=local` | — | Filesystem directory File content is stored under. |
| `file-manager.storage.s3.bucket` | Yes, if `backend=s3` | — | Target S3 bucket name. |
| `file-manager.storage.s3.region` | Yes, if `backend=s3` | — | AWS region (or the region your S3-compatible endpoint expects). |
| `file-manager.storage.s3.endpoint` | No | AWS default | Override endpoint — set this for MinIO or another S3-compatible target. |
| `file-manager.storage.s3.access-key-id` / `.secret-access-key` | No | AWS default credential chain | Explicit static credentials; omit to fall back to the SDK's default credential chain. |
| `file-manager.access-token.max-ttl-seconds.view` | No | `86400` (24h) | Deployment-wide ceiling on a `View`-purpose AccessToken's requested TTL. |
| `file-manager.access-token.max-ttl-seconds.download` | No | `86400` (24h) | Same ceiling, for `Download` purpose. |

An AccessToken's actual expiry (when the caller doesn't set one) defaults to 15 minutes, independent of the ceilings above.

## Provisioning a Tenant

There's no admin API in v1 — provision a Tenant and its first ApiKey via the SQL seed script under [`scripts/`](scripts/).

## Further reading

- [`CONTEXT.md`](CONTEXT.md) — domain glossary
- [`docs/architecture.md`](docs/architecture.md) — module layout, Embedded Mode vs Standalone Service, StorageBackend contract
- [`docs/api-design.md`](docs/api-design.md) — full REST route table, ApiKey auth, Secure vs Non-secure Access
- [`docs/library-usage.md`](docs/library-usage.md) — Embedded Mode usage guide
- [`docs/testing.md`](docs/testing.md) — test expectations
