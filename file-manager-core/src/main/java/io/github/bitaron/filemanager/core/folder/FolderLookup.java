package io.github.bitaron.filemanager.core.folder;

import java.util.UUID;

import io.github.bitaron.filemanager.core.tenant.TenantId;

/**
 * The narrow seam {@link FolderService} depends on: saving a Folder and looking one up by id,
 * scoped to a Tenant. Separated from {@link FolderDao}'s concrete, {@code EntityManager}-backed
 * implementation so the service's own pure-validation tests can fake this out with no database
 * and no Spring context (docs/testing.md), mirroring apikey's {@code ApiKeyLookup}/{@code
 * ApiKeyResolver} seam.
 */
interface FolderLookup {

    Folder save(Folder folder);

    Folder findByIdForTenant(UUID id, TenantId tenantId);
}
