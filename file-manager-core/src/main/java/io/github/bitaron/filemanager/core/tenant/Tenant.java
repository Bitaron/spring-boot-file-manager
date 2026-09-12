package io.github.bitaron.filemanager.core.tenant;

import java.time.Instant;
import java.util.UUID;

import io.github.bitaron.filemanager.core.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The top-level isolation boundary: owns a set of Folders, Files, and ApiKeys (see {@code
 * CONTEXT.md}). Field list is normative per ADR 0003 - don't add columns without updating that
 * ADR. Provisioned exclusively via the operator-run SQL seed script (decision #6); this ticket
 * adds no Java-level create/update path.
 */
@Entity
@Table(name = "tenant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tenant {

    // Not final: JPA providers hydrate these fields via reflection on the protected no-arg
    // constructor above. Outside that, this class exposes no setters - only the static factory.
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Tenant(UUID id, String name, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }

    /** Constructs a new Tenant with a freshly generated UUIDv7 id. */
    public static Tenant create(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tenant name must not be blank");
        }
        return new Tenant(UuidV7.randomUuid(), name, Instant.now());
    }
}
