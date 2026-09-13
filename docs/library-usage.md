# Library usage (Embedded Mode)

How to embed file-manager in a host Spring Boot application. See [architecture.md](architecture.md) for the module layout and [CONTEXT.md](../CONTEXT.md) for the domain glossary.

## Add the starter

Add `file-manager-spring-boot-starter` to your application. It aggregates `file-manager-spring-boot-autoconfigure` (Embedded Mode wiring) with the base Spring Boot starter.

## What the host must supply

A single `DataSource` bean — nothing else. `file-manager-spring-boot-autoconfigure`'s `FolderPersistenceAutoConfiguration` and `ApiKeyPersistenceAutoConfiguration` each activate as soon as exactly one `DataSource` bean is present (`@ConditionalOnSingleCandidate(DataSource.class)`); there is no other required property or bean for Folder persistence or ApiKey resolution.

Given that `DataSource`, the autoconfiguration:

- Folds `Folder`'s and `ApiKey`'s packages (`io.github.bitaron.filemanager.core.folder`, `io.github.bitaron.filemanager.core.apikey`) into the JPA entity scan via Spring Boot's `EntityScanPackages` mechanism — the same one Spring Boot's own autoconfiguration modules use to contribute entity packages. You never need to declare your own `@EntityScan` for file-manager's entities; Spring Boot's stock `HibernateJpaAutoConfiguration` builds one `EntityManagerFactory` (and one persistence unit) covering both your own entities and file-manager's — never a second, separate persistence unit.
- Exposes a shared, transaction-aware `EntityManager` bean (backed by the same `SharedEntityManagerCreator` mechanism `@PersistenceContext` injection uses under the hood), a `FolderService` bean, and an `ApiKeyResolver` bean, all built on that `EntityManager` — unless your application already exposes its own `EntityManager` bean, in which case file-manager's backs off (`@ConditionalOnMissingBean`) and reuses yours.

## Calling FolderService

`FolderService` is a granular, per-aggregate service (see the "Embedded Mode integration shape" decision referenced from `docs/architecture.md`) — call it directly, no HTTP layer involved:

```java
folderService.create(tenantId, actor, "Quarterly Reports", null);       // top-level Folder
folderService.create(tenantId, actor, "2026 Q1", parentFolder.getId()); // nested Folder

folderService.fetch(tenantId, folderId);              // single Folder's metadata, or null if not found
folderService.listChildren(tenantId, parentFolder.getId()); // a Folder's immediate children
folderService.listChildren(tenantId, null);                 // top-level Folders
folderService.listChildren(tenantId, null, afterId, 50);    // one page: id > afterId, at most 50 rows

folderService.rename(tenantId, actor, folderId, "Annual Reports"); // rename in place
folderService.move(tenantId, actor, folderId, newParentId);        // re-parent
folderService.move(tenantId, actor, folderId, null);               // move to top-level
```

A folder-id lookup that can't find a Folder for the given `tenantId` (wrong id, or a different Tenant's Folder) throws `FolderNotFoundException` from `create`/`rename`/`move`, or returns `null` from `fetch`/`listChildren` — see each method's javadoc.

`tenantId` (a `TenantId`) and `actor` (an `Actor`) are always passed explicitly (never inferred from thread-local/security-context state), so `FolderService` works the same whether it's called from a web request or a background job with no HTTP request in flight.

`FolderService` never opens its own transaction — the caller (your application code, typically via `@Transactional`) owns the transaction boundary, the same way it would around any other JPA-backed call.

## Calling ApiKeyResolver

`ApiKeyResolver` (decision #17) resolves a raw, presented ApiKey secret into its Tenant + Actor — the same mechanism the Standalone Service's `/api/v1` filter uses. Embedded Mode only needs this if your own host application wants to authenticate via ApiKey internally; if you already have a resolved `TenantId`/`Actor` (e.g. from your own security context), skip this and call `FolderService` directly.

```java
ApiKeyResolution resolution = apiKeyResolver.resolve(presentedSecret);
if (resolution instanceof ApiKeyResolution.Authenticated authenticated) {
    folderService.create(authenticated.tenantId(), authenticated.actor(), "Quarterly Reports", null);
}
```

A missing, malformed, and revoked ApiKey all resolve to `ApiKeyResolution.NotAuthenticated` indistinguishably — there is no reason code to inspect.

## Transactions

Wrap calls to `FolderService` in your own `@Transactional` boundary (or an existing one already in scope):

```java
@Transactional
public void createQuarterFolder(TenantId tenantId, Actor actor) {
    folderService.create(tenantId, actor, "2026 Q1", null);
}
```

## What's not needed

- No `spring-boot-starter-data-jpa` dependency of your own — the starter brings it transitively.
- No manual `EntityManagerFactory`/persistence-unit configuration.
- No StorageBackend or REST configuration for Folder creation — those belong to other parts of the engine not yet covered here.
- No ApiKey hash lookup of your own — `ApiKeyResolver` already does it, if you want it.

This document grows as later tickets add more Embedded Mode surface (Files, StorageBackend, Trash, AccessTokens).
