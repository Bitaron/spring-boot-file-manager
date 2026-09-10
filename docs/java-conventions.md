# Java & Spring Boot conventions

- Target the latest stable Spring Boot release; keep the parent POM's Spring Boot BOM version current rather than pinning individual Spring dependency versions.
- Use constructor injection everywhere — no field `@Autowired`.
- Domain entities and services use the vocabulary in [CONTEXT.md](../CONTEXT.md) exactly (`Tenant`, `Folder`, `File`, `Visibility`, `AccessToken`, `Trash`, `Actor`, `StorageBackend`) and never the listed "avoid" synonyms (`Directory`, `Blob`, `Document`, `share link`, `storage adapter`, etc.) in class names, method names, or API fields — a reviewer should be able to grep the glossary term and find the code that implements it.
- `file-manager-core` must not depend on `spring-web` or any transport concern — it stays usable from both Embedded Mode and the Standalone Service's controllers.
- Every cross-Tenant operation must take a Tenant id as an explicit parameter (not inferred from thread-local/security-context state alone) so `file-manager-core` stays usable outside a web request thread in Embedded Mode.
- Prefer immutable value objects (Java `record`) for `Visibility`, `Purpose`, and `AccessToken` — they carry no identity of their own beyond what's stated in the glossary.
