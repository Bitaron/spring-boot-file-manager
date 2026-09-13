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
     */
    @Override
    public List<Folder> findChildrenForTenant(UUID parentFolderId, TenantId tenantId) {
        String jpql = "SELECT f FROM Folder f WHERE f.tenantId = :tenantId AND f.parentFolderId "
                + (parentFolderId == null ? "IS NULL" : "= :parentFolderId")
                + " ORDER BY f.createdAt";
        TypedQuery<Folder> query = entityManager.createQuery(jpql, Folder.class);
        query.setParameter("tenantId", tenantId.id());
        if (parentFolderId != null) {
            query.setParameter("parentFolderId", parentFolderId);
        }
        return query.getResultList();
    }
}
