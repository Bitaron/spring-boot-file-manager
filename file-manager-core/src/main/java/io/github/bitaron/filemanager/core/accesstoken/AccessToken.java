package io.github.bitaron.filemanager.core.accesstoken;

import java.time.Instant;
import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A short-lived, non-enumerable credential minted (via an authenticated request) for a specific
 * {@code Public} File, scoped to one {@link Purpose} and carrying its own expiry (see the glossary
 * in {@code CONTEXT.md}). Field list is normative per ADR 0003 - don't add columns without
 * updating that ADR.
 *
 * <p>Unlike every other entity in this codebase, {@link #getToken()} - the opaque, unique, random
 * string itself (decision #14) - is the primary key; there is no separate UUIDv7 surrogate id
 * (ADR 0003's stated exception to its own PK convention).
 *
 * <p>This entity exposes read accessors only - no setters - per docs/architecture.md's "service
 * interfaces, not entity setters" rule. All mutation goes through {@link AccessTokenService}.
 */
@Entity
@Table(
        name = "access_token",
        indexes = {
                @Index(name = "idx_access_token_tenant", columnList = "tenant_id"),
                @Index(name = "idx_access_token_file", columnList = "file_id"),
                @Index(name = "idx_access_token_expires_at", columnList = "expires_at")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessToken {

    // Not final: JPA providers hydrate these fields via reflection on the protected no-arg
    // constructor above. Outside that, this class exposes no setters - only the package-private
    // constructor AccessTokenService uses. tenantId/mintedBy have no generated getter of their
    // own - callers get them wrapped as TenantId/Actor via the methods below, not as raw UUIDs
    // (matching File's/Folder's convention).
    @Id
    @Column(name = "token", nullable = false, updatable = false)
    @Getter
    private String token;

    // FK -> tenant(id) per ADR 0003, enforced at the schema-DDL level, not as a JPA @ManyToOne -
    // core has no need to navigate to the Tenant entity here (matches File's/Folder's convention).
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    // FK -> file(id) per ADR 0003, enforced at the schema-DDL level, not as a JPA @ManyToOne -
    // core has no need to navigate to the File entity here.
    @Column(name = "file_id", nullable = false, updatable = false)
    @Getter
    private UUID fileId;

    @Column(name = "purpose", nullable = false, updatable = false)
    @Enumerated(EnumType.STRING)
    @Getter
    private Purpose purpose;

    @Column(name = "expires_at", nullable = false, updatable = false)
    @Getter
    private Instant expiresAt;

    @Column(name = "minted_at", nullable = false, updatable = false)
    @Getter
    private Instant mintedAt;

    /** Opaque Actor reference - no DB-level foreign key (ADR 0003, decision #10). */
    @Column(name = "minted_by", nullable = false, updatable = false)
    private UUID mintedBy;

    AccessToken(String token, TenantId tenantId, UUID fileId, Purpose purpose, Instant expiresAt,
            Actor mintedBy, Instant mintedAt) {
        this.token = token;
        this.tenantId = tenantId.id();
        this.fileId = fileId;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
        this.mintedAt = mintedAt;
        this.mintedBy = mintedBy.id();
    }

    public TenantId tenantId() {
        return new TenantId(tenantId);
    }

    public Actor mintedBy() {
        return new Actor(mintedBy);
    }
}
