package io.github.bitaron.filemanager.core.file;

import java.util.List;
import java.util.UUID;

import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/**
 * Persistence mechanics for {@link File}, isolated from {@link FileService}'s business rules
 * (decision: file-manager-core's persistence seam is a plain {@link EntityManager}, no Spring
 * Data repository - docs/architecture.md), mirroring {@code core.folder.FolderDao}.
 */
class FileDao implements FileLookup {

    private final EntityManager entityManager;

    FileDao(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public File save(File file) {
        entityManager.persist(file);
        return file;
    }

    /**
     * Looks up a File by id, scoped to {@code tenantId} - a File belonging to a different Tenant
     * is treated as not found (every cross-Tenant lookup must be Tenant-scoped, per
     * docs/java-conventions.md).
     */
    @Override
    public File findByIdForTenant(UUID id, TenantId tenantId) {
        File file = entityManager.find(File.class, id);
        if (file == null || !file.tenantId().equals(tenantId)) {
            return null;
        }
        return file;
    }

    /**
     * Backs the "list a Folder's Files" query the {@code (tenant_id, parent_folder_id)} composite
     * index (ADR 0003) exists to serve, mirroring {@code FolderDao#findChildrenForTenant(UUID,
     * TenantId)} - minus its {@code IS NULL} branch, since a File's {@code parentFolderId} is never
     * {@code null} (every File belongs to exactly one Folder), so it's always bound as a plain query
     * parameter.
     */
    @Override
    public List<File> findByParentFolderForTenant(UUID parentFolderId, TenantId tenantId) {
        TypedQuery<File> query = entityManager.createQuery(
                "SELECT f FROM File f WHERE f.tenantId = :tenantId AND f.parentFolderId = :parentFolderId "
                        + "ORDER BY f.createdAt",
                File.class);
        query.setParameter("tenantId", tenantId.id());
        query.setParameter("parentFolderId", parentFolderId);
        return query.getResultList();
    }

    /**
     * Same shape as {@link #findByParentFolderForTenant(UUID, TenantId)}, plus an id-ascending
     * order (the composite index already used above serves this ordering too, per ADR 0003's
     * time-ordered UUIDv7 rationale) and an optional {@code id > :afterId} predicate for the
     * cursor, mirroring {@code FolderDao#findChildrenForTenant(UUID, TenantId, UUID, int)}.
     */
    @Override
    public List<File> findByParentFolderForTenant(
            UUID parentFolderId, TenantId tenantId, UUID afterId, int limit) {
        String jpql = "SELECT f FROM File f WHERE f.tenantId = :tenantId AND f.parentFolderId = :parentFolderId"
                + (afterId == null ? "" : " AND f.id > :afterId")
                + " ORDER BY f.id ASC";
        TypedQuery<File> query = entityManager.createQuery(jpql, File.class);
        query.setParameter("tenantId", tenantId.id());
        query.setParameter("parentFolderId", parentFolderId);
        if (afterId != null) {
            query.setParameter("afterId", afterId);
        }
        query.setMaxResults(limit);
        return query.getResultList();
    }
}
