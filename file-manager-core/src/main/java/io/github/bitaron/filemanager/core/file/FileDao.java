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
     * Permanently deletes this File's row (issue #38: Purge). {@code entityManager.remove} requires
     * a managed entity; {@code file} here is always the very instance {@link #findByIdForTenant}
     * handed back from this same {@code entityManager}'s persistence context (the only path
     * {@link FileService#purge} takes to obtain it), so it is already managed and can be removed
     * directly - no {@code contains}/{@code merge} dance needed.
     */
    @Override
    public void delete(File file) {
        entityManager.remove(file);
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
     *
     * <p>Excludes Files with their own {@code trashedAt} set (issue #37, ADR 0004: ordinary
     * listings silently exclude trashed items), mirroring {@code FolderDao}'s same predicate. The
     * ancestor-trashed half (an entire subtree disappearing) is handled by the caller
     * short-circuiting to an empty list before this query even runs.
     */
    @Override
    public List<File> findByParentFolderForTenant(UUID parentFolderId, TenantId tenantId) {
        TypedQuery<File> query = entityManager.createQuery(
                "SELECT f FROM File f WHERE f.tenantId = :tenantId AND f.parentFolderId = :parentFolderId "
                        + "AND f.trashedAt IS NULL ORDER BY f.createdAt",
                File.class);
        query.setParameter("tenantId", tenantId.id());
        query.setParameter("parentFolderId", parentFolderId);
        return query.getResultList();
    }

    /**
     * Same shape as {@link #findByParentFolderForTenant(UUID, TenantId)}, plus an id-ascending
     * order (the composite index already used above serves this ordering too, per ADR 0003's
     * time-ordered UUIDv7 rationale) and an optional {@code id > :afterId} predicate for the
     * cursor, mirroring {@code FolderDao#findChildrenForTenant(UUID, TenantId, UUID, int)}. Same
     * trashed-exclusion predicate as above.
     */
    @Override
    public List<File> findByParentFolderForTenant(
            UUID parentFolderId, TenantId tenantId, UUID afterId, int limit) {
        String jpql = "SELECT f FROM File f WHERE f.tenantId = :tenantId AND f.parentFolderId = :parentFolderId"
                + " AND f.trashedAt IS NULL"
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

    /**
     * Same shape as {@link #findByParentFolderForTenant(UUID, TenantId)}, minus its
     * {@code trashedAt IS NULL} predicate (issue #38: {@code FileService#purgeAllInFolder} must
     * purge every File under a Folder regardless of that File's own trashed state), mirroring
     * {@code FolderDao#findAllChildrenForTenant}.
     */
    @Override
    public List<File> findAllByParentFolderForTenant(UUID parentFolderId, TenantId tenantId) {
        TypedQuery<File> query = entityManager.createQuery(
                "SELECT f FROM File f WHERE f.tenantId = :tenantId AND f.parentFolderId = :parentFolderId "
                        + "ORDER BY f.createdAt",
                File.class);
        query.setParameter("tenantId", tenantId.id());
        query.setParameter("parentFolderId", parentFolderId);
        return query.getResultList();
    }

    /**
     * Backs the trash bin's own query (issue #37): {@code trashedAt IS NOT NULL} unconditionally,
     * plus an optional {@code parentFolderId} branch, mirroring
     * {@code FolderDao#findTrashedForTenant} exactly.
     */
    @Override
    public List<File> findTrashedForTenant(UUID parentFolderId, TenantId tenantId) {
        String jpql = "SELECT f FROM File f WHERE f.tenantId = :tenantId AND f.trashedAt IS NOT NULL"
                + (parentFolderId == null ? "" : " AND f.parentFolderId = :parentFolderId")
                + " ORDER BY f.trashedAt DESC";
        TypedQuery<File> query = entityManager.createQuery(jpql, File.class);
        query.setParameter("tenantId", tenantId.id());
        if (parentFolderId != null) {
            query.setParameter("parentFolderId", parentFolderId);
        }
        return query.getResultList();
    }
}
