package io.github.bitaron.filemanager.core.file;

import java.util.UUID;

import io.github.bitaron.filemanager.core.tenant.TenantId;

/**
 * The narrow seam {@link FileService} depends on: saving a File and looking one up by id, scoped
 * to a Tenant. No listing methods (ticket #34). Separated from {@code FileDao}'s concrete,
 * {@code EntityManager}-backed implementation so the service's own tests can fake this out with no
 * database, mirroring {@code core.folder}'s {@code FolderLookup}/{@code FolderDao} seam.
 */
interface FileLookup {

    File save(File file);

    File findByIdForTenant(UUID id, TenantId tenantId);
}
