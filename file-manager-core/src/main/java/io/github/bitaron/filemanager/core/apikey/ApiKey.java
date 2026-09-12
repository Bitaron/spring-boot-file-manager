package io.github.bitaron.filemanager.core.apikey;

import java.time.Instant;
import java.util.UUID;

import io.github.bitaron.filemanager.core.id.UuidV7;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A credential scoped to exactly one Tenant, used to make Secure Access calls (see {@code
 * CONTEXT.md}). Field list is normative per ADR 0003 - don't add columns without updating that
 * ADR.
 *
 * <p>Only {@link #keyHash} - a SHA-256 digest, never the plaintext secret - is ever persisted
 * (decision #15). Provisioned via the operator-run SQL seed script (decision #6); revoked by
 * setting {@link #revokedAt} via a direct SQL {@code UPDATE}, also out-of-band - a row is never
 * deleted, so {@link #revoke()} exists for tests and future in-process callers, not because this
 * ticket wires up a Java revocation path.
 */
@Entity
@Table(
        name = "api_key",
        indexes = {
                @Index(name = "ix_api_key_tenant_id", columnList = "tenant_id"),
                @Index(name = "ux_api_key_key_hash", columnList = "key_hash", unique = true)
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiKey {

    // Not final: JPA providers hydrate these fields via reflection on the protected no-arg
    // constructor above. Outside that, this class exposes no setters - only the static factory
    // and the revoke() domain method. tenantId has no generated getter of its own - callers get
    // it wrapped as a TenantId via the tenantId() method below, not as a raw UUID.
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @Getter
    private UUID id;

    // FK -> tenant(id) per ADR 0003, enforced at the schema-DDL level (not decided by this
    // ticket), not as a JPA @ManyToOne - core has no need to navigate to the Tenant entity here.
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "label")
    @Getter
    private String label;

    @Column(name = "key_hash", nullable = false, updatable = false, columnDefinition = "CHAR(64)")
    @Getter
    private String keyHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Getter
    private Instant createdAt;

    @Column(name = "revoked_at")
    @Getter
    private Instant revokedAt;

    private ApiKey(UUID id, UUID tenantId, String label, String keyHash, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.label = label;
        this.keyHash = keyHash;
        this.createdAt = createdAt;
    }

    /** Issues a new, active ApiKey for the given Tenant, storing only the secret's hash. */
    public static ApiKey issue(TenantId tenantId, String label, String keyHash) {
        if (tenantId == null) {
            throw new IllegalArgumentException("Tenant id must not be null");
        }
        if (keyHash == null || keyHash.length() != 64) {
            throw new IllegalArgumentException("keyHash must be a 64-character SHA-256 hex digest");
        }
        return new ApiKey(UuidV7.randomUuid(), tenantId.id(), label, keyHash, Instant.now());
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Marks this key revoked as of now; idempotent - does not overwrite an earlier revocation. */
    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }

    public TenantId tenantId() {
        return new TenantId(tenantId);
    }
}
