package io.github.bitaron.filemanager.core.folder;

import java.util.List;
import java.util.UUID;

import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/**
 * Persistence mechanics for {@link Folder}, isolated from {@link FolderService}'s business
 * rules (decision: file-manager-core's persistence seam is a plain {@link EntityManager}, no
 * Spring Data repository - docs/architecture.md).
 */
class FolderDao implements FolderLookup {

    private final EntityManager entityManager;

    FolderDao(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Folder save(Folder folder) {
        entityManager.persist(folder);
        return folder;
    }

    /**
     * Looks up a Folder by id, scoped to {@code tenantId} - a Folder belonging to a different
     * Tenant is treated as not found (every cross-Tenant lookup must be Tenant-scoped, per
     * docs/java-conventions.md).
     */
    @Override
    public Folder findByIdForTenant(UUID id, TenantId tenantId) {
        Folder folder = entityManager.find(Folder.class, id);
        if (folder == null || !folder.tenantId().equals(tenantId)) {
            return null;
        }
        return folder;
    }

    /**
     * Backs the "list children"/"list top-level" query the {@code (tenant_id, parent_folder_id)}
     * composite index (ADR 0003) exists to serve. {@code parentFolderId} is branched on rather
     * than bound as a query parameter because JPQL's {@code =} never matches {@code NULL} -
     * top-level Folders need an {@code IS NULL} predicate instead.
     *
     * <p>Excludes Folders with their own {@code trashedAt} set (issue #37, ADR 0004: ordinary
     * listings silently exclude trashed items) - the cheap, per-row half of the exclusion rule.
     * The ancestor-trashed half (an entire subtree disappearing) is handled by the caller
     * short-circuiting to an empty list before this query even runs.
     */
    @Override
    public List<Folder> findChildrenForTenant(UUID parentFolderId, TenantId tenantId) {
        String jpql = "SELECT f FROM Folder f WHERE f.tenantId = :tenantId AND f.parentFolderId "
                + (parentFolderId == null ? "IS NULL" : "= :parentFolderId")
                + " AND f.trashedAt IS NULL"
                + " ORDER BY f.createdAt";
        TypedQuery<Folder> query = entityManager.createQuery(jpql, Folder.class);
        query.setParameter("tenantId", tenantId.id());
        if (parentFolderId != null) {
            query.setParameter("parentFolderId", parentFolderId);
        }
        return query.getResultList();
    }

    /**
     * Same shape as {@link #findChildrenForTenant(UUID, TenantId)}, plus an id-ascending order
     * (the composite index already used above serves this ordering too, per ADR 0003's
     * time-ordered UUIDv7 rationale) and an optional {@code id > :afterId} predicate for the
     * cursor. Same trashed-exclusion predicate as above.
     */
    @Override
    public List<Folder> findChildrenForTenant(
            UUID parentFolderId, TenantId tenantId, UUID afterId, int limit) {
        String jpql = "SELECT f FROM Folder f WHERE f.tenantId = :tenantId AND f.parentFolderId "
                + (parentFolderId == null ? "IS NULL" : "= :parentFolderId")
                + " AND f.trashedAt IS NULL"
                + (afterId == null ? "" : " AND f.id > :afterId")
                + " ORDER BY f.id ASC";
        TypedQuery<Folder> query = entityManager.createQuery(jpql, Folder.class);
        query.setParameter("tenantId", tenantId.id());
        if (parentFolderId != null) {
            query.setParameter("parentFolderId", parentFolderId);
        }
        if (afterId != null) {
            query.setParameter("afterId", afterId);
        }
        query.setMaxResults(limit);
        return query.getResultList();
    }

    /**
     * Backs the trash bin's own query (issue #37): {@code trashedAt IS NOT NULL} unconditionally,
     * plus an optional {@code parentFolderId} branch - unlike {@link #findChildrenForTenant}'s
     * branch, this one is omittable entirely rather than always present, since {@code null} here
     * means a flat, tenant-wide scan rather than "top-level".
     */
    @Override
    public List<Folder> findTrashedForTenant(UUID parentFolderId, TenantId tenantId) {
        String jpql = "SELECT f FROM Folder f WHERE f.tenantId = :tenantId AND f.trashedAt IS NOT NULL"
                + (parentFolderId == null ? "" : " AND f.parentFolderId = :parentFolderId")
                + " ORDER BY f.trashedAt DESC";
        TypedQuery<Folder> query = entityManager.createQuery(jpql, Folder.class);
        query.setParameter("tenantId", tenantId.id());
        if (parentFolderId != null) {
            query.setParameter("parentFolderId", parentFolderId);
        }
        return query.getResultList();
    }
}
