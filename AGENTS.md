# File Manager

A multi-tenant, Drive-like hierarchical file-management engine — usable as an embeddable Spring Boot library or a standalone REST service — with swappable storage backends.

## Package manager

Maven (multi-module reactor build).

## Build & typecheck

- Build every module: `mvn install`
- Compile only (fast typecheck, no tests): `mvn compile`
- Test a single module: `mvn -pl <module> test`

## Plan Mode

- Make the plan extremely concise. Sacrifice grammar for the sake of concision.
- At the end of each plan, give me a list of unresolved questions to answer, if any.

## Further reading

- [Domain language](CONTEXT.md) — canonical terms (Tenant, Folder, File, Visibility, Secure/Non-secure Access, AccessToken, Trash, Actor, StorageBackend, Embedded Mode, Standalone Service) and the terms to avoid in code and docs.
- [Architecture](docs/architecture.md) — Maven module layout, Embedded Mode vs Standalone Service, the StorageBackend contract, deep-module boundaries.
- [Java & Spring Boot conventions](docs/java-conventions.md)
- [API design](docs/api-design.md) — REST surface, Swagger/OpenAPI, ApiKey authentication, Secure vs Non-secure Access.
- [Testing](docs/testing.md) — unit and integration test expectations.
- [Git workflow](docs/git-workflow.md)

