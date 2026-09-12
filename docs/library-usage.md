# Library usage (Embedded Mode)

How to embed file-manager in a host Spring Boot application. See [architecture.md](architecture.md) for the module layout and [CONTEXT.md](../CONTEXT.md) for the domain glossary.

## Add the starter

Add `file-manager-spring-boot-starter` to your application. It aggregates `file-manager-spring-boot-autoconfigure` (Embedded Mode wiring) with the base Spring Boot starter.

## What the host must supply

A single `DataSource` bean — nothing else. `file-manager-spring-boot-autoconfigure`'s `FolderPersistenceAutoConfiguration` activates as soon as exactly one `DataSource` bean is present (`@ConditionalOnSingleCandidate(DataSource.class)`); there is no other required property or bean for Folder persistence.

Given that `DataSource`, the autoconfiguration:

- Folds `Folder`'s package (`io.github.bitaron.filemanager.core.folder`) into the JPA entity scan via Spring Boot's `EntityScanPackages` mechanism — the same one Spring Boot's own autoconfiguration modules use to contribute entity packages. You never need to declare your own `@EntityScan` for file-manager's entities; Spring Boot's stock `HibernateJpaAutoConfiguration` builds one `EntityManagerFactory` (and one persistence unit) covering both your own entities and file-manager's — never a second, separate persistence unit.
- Exposes a shared, transaction-aware `EntityManager` bean (backed by the same `SharedEntityManagerCreator` mechanism `@PersistenceContext` injection uses under the hood) and a `FolderService` bean built on it — unless your application already exposes its own `EntityManager` bean, in which case file-manager's backs off (`@ConditionalOnMissingBean`) and reuses yours.

## Calling FolderService

`FolderService` is a granular, per-aggregate service (see the "Embedded Mode integration shape" decision referenced from `docs/architecture.md`) — call it directly, no HTTP layer involved:

```java
folderService.create(tenantId, actorId, "Quarterly Reports", null);       // top-level Folder
folderService.create(tenantId, actorId, "2026 Q1", parentFolder.getId()); // nested Folder
```

`tenantId` and `actorId` are always passed explicitly (never inferred from thread-local/security-context state), so `FolderService` works the same whether it's called from a web request or a background job with no HTTP request in flight.

`FolderService` never opens its own transaction — the caller (your application code, typically via `@Transactional`) owns the transaction boundary, the same way it would around any other JPA-backed call.

## Transactions

Wrap calls to `FolderService` in your own `@Transactional` boundary (or an existing one already in scope):

```java
@Transactional
public void createQuarterFolder(UUID tenantId, UUID actorId) {
    folderService.create(tenantId, actorId, "2026 Q1", null);
}
```

## What's not needed

- No `spring-boot-starter-data-jpa` dependency of your own — the starter brings it transitively.
- No manual `EntityManagerFactory`/persistence-unit configuration.
- No ApiKey, StorageBackend, or REST configuration for Folder creation — those belong to other parts of the engine not yet covered here.

This document grows as later tickets add more Embedded Mode surface (list/fetch, rename/move, Files, StorageBackend, Trash, AccessTokens).
