package io.github.bitaron.filemanager.core.folder;

import java.util.List;
import java.util.UUID;

import io.github.bitaron.filemanager.core.tenant.TenantId;

/**
 * The narrow seam {@link FolderService} depends on: saving a Folder, looking one up by id, and
 * listing a Folder's immediate children - all scoped to a Tenant. Separated from
 * {@link FolderDao}'s concrete, {@code EntityManager}-backed implementation so the service's own
 * pure-validation tests can fake this out with no database and no Spring context
 * (docs/testing.md), mirroring apikey's {@code ApiKeyLookup}/{@code ApiKeyResolver} seam.
 */
interface FolderLookup {

    Folder save(Folder folder);

    Folder findByIdForTenant(UUID id, TenantId tenantId);

    /**
     * @param parentFolderId the parent Folder's id, or {@code null} to list top-level Folders
     */
    List<Folder> findChildrenForTenant(UUID parentFolderId, TenantId tenantId);

    /**
     * One page of a Folder's immediate children, ordered by id ascending (UUIDv7 is
     * time-ordered, ADR 0003) - backs the Standalone Service's cursor pagination (ADR 0004).
     *
     * @param parentFolderId the parent Folder's id, or {@code null} to list top-level Folders
     * @param afterId only Folders whose id sorts after this one, or {@code null} to start from
     *     the beginning
     * @param limit the maximum number of Folders to return
     */
    List<Folder> findChildrenForTenant(UUID parentFolderId, TenantId tenantId, UUID afterId, int limit);
}
