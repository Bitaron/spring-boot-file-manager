# Java & Spring Boot conventions

- Baseline: Java 25 (current LTS), Spring Boot 4.1.1 (current stable). Target the latest stable Spring Boot release going forward; keep the parent POM's Spring Boot BOM version current rather than pinning individual Spring dependency versions.
- Pin Lombok to `1.18.42` or newer — earlier versions are incompatible with JDK 25 (see [ADR 0001](adr/0001-java-spring-boot-baseline.md)).
- Use constructor injection everywhere — no field `@Autowired`.
- Domain entities and services use the vocabulary in [CONTEXT.md](../CONTEXT.md) exactly (`Tenant`, `Folder`, `File`, `Visibility`, `AccessToken`, `Trash`, `Actor`, `StorageBackend`) and never the listed "avoid" synonyms (`Directory`, `Blob`, `Document`, `share link`, `storage adapter`, etc.) in class names, method names, or API fields — a reviewer should be able to grep the glossary term and find the code that implements it.
- `file-manager-core` must not depend on `spring-web` or any transport concern — it stays usable from both Embedded Mode and the Standalone Service's controllers.
- Every cross-Tenant operation must take a Tenant id as an explicit parameter (not inferred from thread-local/security-context state alone) so `file-manager-core` stays usable outside a web request thread in Embedded Mode.
- Prefer immutable value objects for closed-set attributes: `Visibility` and `Purpose` are plain Java `enum`s (a fixed set of named values, not data-carrying), the idiomatic choice over a record wrapper. `AccessToken` (like `File`/`Folder`) is a JPA `@Entity`, not a record — unlike `Visibility`/`Purpose`, it *does* carry its own identity (its `token` column is its primary key, per ADR 0003), plus JPA's persistence lifecycle requires the entity shape.
