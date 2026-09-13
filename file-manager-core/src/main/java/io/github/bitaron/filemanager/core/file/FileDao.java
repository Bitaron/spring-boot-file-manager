package io.github.bitaron.filemanager.core.file;

import java.util.UUID;

import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.EntityManager;

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
}
