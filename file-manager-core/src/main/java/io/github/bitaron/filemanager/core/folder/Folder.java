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
        // ADR 0003 calls tenant_id and parent_folder_id "indexed" individually, plus a composite
        // (tenant_id, parent_folder_id) index for "list children" queries. Neither needs its own
        // standalone index on top of the composite: a B-tree index serves any left-prefix of its
        // columns, so the composite alone already covers tenant_id-only lookups (a dedicated
        // single-column index would just duplicate it, at pure write/storage cost). No
        // parent_folder_id-only index either: every lookup in this codebase is Tenant-scoped
        // (docs/java-conventions.md), so a parent_folder_id-only query - the one shape the
        // composite's non-leading column wouldn't serve - never occurs.
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

    // FK -> tenant(id) per ADR 0003, enforced at the schema-DDL level, not as a JPA
    // @ManyToOne - core has no need to navigate to the Tenant entity here (matches ApiKey's
    // convention).
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

    /**
     * Renames this Folder in place - a single-row update, per ADR 0003/0004. No uniqueness check
     * against sibling names (ADR 0003: duplicate sibling names are legal).
     */
    void rename(String name, Actor actor, Instant now) {
        this.name = name;
        this.updatedAt = now;
        this.lastModifiedBy = actor.id();
    }

    /**
     * Re-parents this Folder in place - a single-row update, per ADR 0003/0004.
     *
     * @param parentFolderId the new parent Folder's id, or {@code null} to move to top-level
     */
    void moveTo(UUID parentFolderId, Actor actor, Instant now) {
        this.parentFolderId = parentFolderId;
        this.updatedAt = now;
        this.lastModifiedBy = actor.id();
    }

    /**
     * Trashes this Folder in place - a single-row update, per ADR 0003/0004. Per decision #16,
     * this only ever writes this Folder's own {@code trashedAt}/{@code trashedBy} - descendants
     * are never touched; their "effectively trashed" state is computed at read time instead.
     *
     * <p>Idempotent (issue #37): if this Folder is already trashed, this is a full no-op - none
     * of {@code trashedAt}/{@code trashedBy}/{@code updatedAt}/{@code lastModifiedBy} are
     * touched, so a redundant call never overwrites the original trash and never produces a
     * spurious "last modified" change.
     */
    void trash(Actor actor, Instant now) {
        if (trashedAt != null) {
            return;
        }
        this.trashedAt = now;
        this.trashedBy = actor.id();
        this.updatedAt = now;
        this.lastModifiedBy = actor.id();
    }

    /**
     * Restores this Folder in place - a single-row update, per ADR 0003/0004. Per decision #16,
     * this only ever clears this Folder's own {@code trashedAt}/{@code trashedBy} - it never
     * checks or touches ancestor state, so a descendant trashed independently of an ancestor stays
     * trashed after that ancestor is restored.
     *
     * <p>Idempotent (issue #37): if this Folder is already active (not trashed), this is a full
     * no-op - {@code updatedAt}/{@code lastModifiedBy} are left untouched too.
     */
    void restore(Actor actor, Instant now) {
        if (trashedAt == null) {
            return;
        }
        this.trashedAt = null;
        this.trashedBy = null;
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
