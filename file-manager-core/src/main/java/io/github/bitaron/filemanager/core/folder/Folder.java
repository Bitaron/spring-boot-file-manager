package io.github.bitaron.filemanager.core.folder;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/**
 * A Tenant-scoped, hierarchical container for Files and other Folders (see the glossary in
 * {@code CONTEXT.md}).
 *
 * <p>Folder hierarchy is a plain adjacency list (ADR 0003): {@link #parentFolderId} is
 * {@code null} for a top-level Folder, or the id of the parent Folder otherwise.
 *
 * <p>This entity exposes read accessors only - no setters - per docs/architecture.md's
 * "service interfaces, not entity setters" rule. All mutation goes through
 * {@link FolderService}.
 */
@Entity
@Table(
        name = "folder",
        indexes = @Index(name = "idx_folder_tenant_parent", columnList = "tenant_id, parent_folder_id"))
public class Folder {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** {@code null} means this Folder is top-level (ADR 0003). */
    @Column(name = "parent_folder_id")
    private UUID parentFolderId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Opaque Actor reference - no DB-level foreign key (ADR 0003, decision #10). */
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Opaque Actor reference - no DB-level foreign key (ADR 0003, decision #10). */
    @Column(name = "last_modified_by", nullable = false)
    private UUID lastModifiedBy;

    @Column(name = "trashed_at")
    private Instant trashedAt;

    /** Opaque Actor reference - no DB-level foreign key (ADR 0003, decision #10). */
    @Column(name = "trashed_by")
    private UUID trashedBy;

    /** For JPA only. */
    protected Folder() {
    }

    Folder(UUID tenantId, UUID parentFolderId, String name, UUID actorId, Instant now) {
        this.tenantId = tenantId;
        this.parentFolderId = parentFolderId;
        this.name = name;
        this.createdAt = now;
        this.createdBy = actorId;
        this.updatedAt = now;
        this.lastModifiedBy = actorId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getParentFolderId() {
        return parentFolderId;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getLastModifiedBy() {
        return lastModifiedBy;
    }

    public Instant getTrashedAt() {
        return trashedAt;
    }

    public UUID getTrashedBy() {
        return trashedBy;
    }
}
