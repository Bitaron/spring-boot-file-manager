# Testing

## Unit tests

- Every module ships its own unit tests; `file-manager-core`'s domain rules (Tenant isolation, Visibility-gated access, Trash lifecycle) are tested with no Spring context and no real StorageBackend — fake/in-memory implementations only.
- A new `StorageBackend` implementation gets a shared contract-test suite (in `file-manager-test-support`) run against every backend, so local-disk and S3-compatible implementations are held to the same behavior.

## Integration tests

- `file-manager-service` integration tests exercise the full Standalone Service: REST controller → core → real StorageBackend (e.g. via Testcontainers for an S3-compatible target and a real database), asserting on HTTP status/body, not just service-layer return values.
- Cover both access paths end-to-end at least once each: a Secure Access request with a valid/invalid ApiKey, and a Non-secure Access request with a valid/expired/wrong-Purpose AccessToken.
- Embedded Mode gets its own integration test that boots the Spring Boot starter in a plain (non-service) application context, proving the autoconfigure works without the REST layer present.

## Suggested docs/ folder layout

```
docs/
├── architecture.md        # this instruction set (agent-facing)
├── java-conventions.md
├── api-design.md
├── testing.md
├── git-workflow.md
├── library-usage.md       # human-facing: how to embed the Spring Boot starter
└── adr/                   # architecture decision records, one file per decision
```
