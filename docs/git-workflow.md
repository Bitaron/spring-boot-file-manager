# Git workflow

- Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`) — the module a change touches goes in the scope, e.g. `feat(storage-s3): ...`, `fix(core): ...`.
- Keep a commit scoped to one Maven module where possible; a change that must cross modules (e.g. a new StorageBackend method plus its implementations) is still one commit, but say so in the body.
- Branch names: `<type>/<short-description>`, e.g. `feat/access-token-purpose`.
- New StorageBackend implementations, new AccessToken purposes, or any change to the Secure/Non-secure Access boundary should note the ADR (see `docs/adr/`) it follows or add one.
