package io.github.bitaron.filemanager.core.folder;

import java.time.Instant;
import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
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
 * A Tenant-scoped, hierarchical container for Files and other Folders (see the glossary in
 * {@code CONTEXT.md}). Field list is normative per ADR 0003 - don't add columns without updating
 * that ADR.
 *
 * <p>Folder hierarchy is a plain adjacency list (ADR 0003): {@link #getParentFolderId()} is
 * {@code null} for a top-level Folder, or the id of the parent Folder otherwise.
 *
 * <p>This entity exposes read accessors only - no setters - per docs/architecture.md's "service
 * interfaces, not entity setters" rule. All mutation goes through {@link FolderService}.
 */
@Entity
@Table(
        name = "folder",
        indexes = @Index(name = "idx_folder_tenant_parent", columnList = "tenant_id, parent_folder_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Folder {

    // Not final: JPA providers hydrate these fields via reflection on the protected no-arg
    // constructor above. Outside that, this class exposes no setters - only the package-private
    // constructor FolderService uses. tenantId/createdBy/lastModifiedBy/trashedBy have no
    // generated getter of their own - callers get them wrapped as TenantId/Actor via the methods
    // below, not as raw UUIDs (matching ApiKey's convention).
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @Getter
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** {@code null} means this Folder is top-level (ADR 0003). */
    @Column(name = "parent_folder_id")
    @Getter
    private UUID parentFolderId;

    @Column(name = "name", nullable = false)
    @Getter
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Getter
    private Instant createdAt;

    /** Opaque Actor reference - no DB-level foreign key (ADR 0003, decision #10). */
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    @Getter
    private Instant updatedAt;

    /** Opaque Actor reference - no DB-level foreign key (ADR 0003, decision #10). */
    @Column(name = "last_modified_by", nullable = false)
    private UUID lastModifiedBy;

    @Column(name = "trashed_at")
    @Getter
    private Instant trashedAt;

    /** Opaque Actor reference - no DB-level foreign key (ADR 0003, decision #10). */
    @Column(name = "trashed_by")
    private UUID trashedBy;

    Folder(TenantId tenantId, UUID parentFolderId, String name, Actor actor, Instant now) {
        this.id = UuidV7.randomUuid();
        this.tenantId = tenantId.id();
        this.parentFolderId = parentFolderId;
        this.name = name;
        this.createdAt = now;
        this.createdBy = actor.id();
        this.updatedAt = now;
        this.lastModifiedBy = actor.id();
    }

    public TenantId tenantId() {
        return new TenantId(tenantId);
    }

    public Actor createdBy() {
        return new Actor(createdBy);
    }

    public Actor lastModifiedBy() {
        return new Actor(lastModifiedBy);
    }

    /** {@code null} until this Folder is trashed. */
    public Actor trashedBy() {
        return trashedBy == null ? null : new Actor(trashedBy);
    }
}
