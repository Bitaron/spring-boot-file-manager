package io.github.bitaron.filemanager.core.folder;

import java.util.UUID;

import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.EntityManager;

/**
 * Persistence mechanics for {@link Folder}, isolated from {@link FolderService}'s business
 * rules (decision: file-manager-core's persistence seam is a plain {@link EntityManager}, no
 * Spring Data repository - docs/architecture.md).
 */
class FolderDao {

    private final EntityManager entityManager;

    FolderDao(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    Folder save(Folder folder) {
        entityManager.persist(folder);
        return folder;
    }

    /**
     * Looks up a Folder by id, scoped to {@code tenantId} - a Folder belonging to a different
     * Tenant is treated as not found (every cross-Tenant lookup must be Tenant-scoped, per
     * docs/java-conventions.md).
     */
    Folder findByIdForTenant(UUID id, TenantId tenantId) {
        Folder folder = entityManager.find(Folder.class, id);
        if (folder == null || !folder.tenantId().equals(tenantId)) {
            return null;
        }
        return folder;
    }
}
