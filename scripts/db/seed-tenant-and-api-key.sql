-- Seed a new Tenant plus its first ApiKey (decision #6: provisioning is out-of-band, no admin
-- API). Reusable across database engines (Postgres, MySQL, H2, ...) - no database-native hash
-- function is used, since no engine is pinned by this spec (decision #15). Assumes the `tenant`
-- and `api_key` tables already exist (see ADR 0003 for their field lists).
--
-- Usage:
--   1. Generate a new ApiKey secret in the `fm_<43 base64url chars>` format, e.g.:
--        KEY="fm_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
--      Hand this value to the tenant once, out-of-band. It is never stored anywhere - only
--      its hash is.
--   2. Compute the secret's SHA-256 hex digest outside SQL (this script never relies on a
--      database-native hash function):
--        echo -n "$KEY" | sha256sum | cut -d' ' -f1
--   3. Fill in the placeholders below (TENANT_ID and API_KEY_ID are fresh UUIDv7 values - see
--      ADR 0003; any UUIDv7 generator works, e.g. the one in
--      io.github.bitaron.filemanager.core.id.UuidV7) and run the resulting statements against
--      the target database.
--
-- Revocation is likewise out-of-band, via a single UPDATE - never a DELETE (decision #15):
--   UPDATE api_key SET revoked_at = CURRENT_TIMESTAMP WHERE id = '{{API_KEY_ID}}';

INSERT INTO tenant (id, name, created_at)
VALUES ('{{TENANT_ID}}', '{{TENANT_NAME}}', CURRENT_TIMESTAMP);

INSERT INTO api_key (id, tenant_id, label, key_hash, created_at, revoked_at)
VALUES ('{{API_KEY_ID}}', '{{TENANT_ID}}', '{{API_KEY_LABEL}}', '{{API_KEY_HASH}}', CURRENT_TIMESTAMP, NULL);
