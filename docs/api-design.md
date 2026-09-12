# API design

## Authentication

Every Standalone Service endpoint is authenticated by an `ApiKey`, scoped to exactly one Tenant (see [glossary](../CONTEXT.md)). Resolve the ApiKey to its Tenant before any Folder/File lookup — never accept a Tenant id from the request body or path as the sole isolation check.

## Secure vs Non-secure Access

- **Secure Access** (authenticated, ApiKey-bearing) is the only path for a `Private` File's content.
- **Non-secure Access** is the only path for a `Public` File's content, via a short-lived `AccessToken` bearing a `Purpose` (`View` or `Download`). Minting an AccessToken is itself an authenticated (Secure) call; redeeming it is not.
- Never expose a `Private` File over an unauthenticated URL, and never require an ApiKey to redeem an AccessToken.

## REST surface (Standalone Service)

- Document every endpoint with Swagger/OpenAPI (springdoc-openapi) — this is a v1 requirement, not optional polish.
- Version the API path (e.g. `/api/v1/...`) so the REST surface can evolve independently of `file-manager-core`'s Java API.
- Controllers translate HTTP concerns (status codes, pagination params) onto `file-manager-core` service calls; validation of request shape belongs in the controller layer, domain rule validation belongs in core.
- Full route table, DTO shapes, pagination, error-response, and HTTP status-code conventions (including the deliberately non-standard `404`-not-`403`/`410` handling that preserves non-enumerability): see [ADR 0004](adr/0004-rest-api-resource-design.md).

## Library API (Embedded Mode)

Ship a separate library-usage guide (`docs/library-usage.md`, see the [suggested docs/ layout](testing.md#suggested-docs-folder-layout)) covering autoconfigure properties and the minimal beans a consumer must supply (`DataSource`, `StorageBackend`). Keep the public Java API of `file-manager-core` the same whether called from the starter or from the Standalone Service's controllers — there is one API, two ways to reach it.
