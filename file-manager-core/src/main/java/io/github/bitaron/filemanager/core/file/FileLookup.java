package io.github.bitaron.filemanager.core.file;

import java.util.List;
import java.util.UUID;

import io.github.bitaron.filemanager.core.tenant.TenantId;

/**
 * The narrow seam {@link FileService} depends on: saving a File, looking one up by id, and
 * listing a Folder's Files - all scoped to a Tenant. Separated from {@code FileDao}'s concrete,
 * {@code EntityManager}-backed implementation so the service's own tests can fake this out with no
 * database, mirroring {@code core.folder}'s {@code FolderLookup}/{@code FolderDao} seam.
 */
interface FileLookup {

    File save(File file);

    /**
     * Permanently deletes this File's metadata row (issue #38: Purge, the only way content is ever
     * permanently removed - decision #7). Callers are responsible for having already deleted the
     * corresponding {@code StorageBackend} content, and for having already checked this File is
     * actually trashed - this method itself performs neither check.
     */
    void delete(File file);

    File findByIdForTenant(UUID id, TenantId tenantId);

    /**
     * @param parentFolderId the parent Folder's id - never {@code null}, unlike Folder's own
     *     {@code parentFolderId} (ADR 0003: every File belongs to exactly one Folder)
     */
    List<File> findByParentFolderForTenant(UUID parentFolderId, TenantId tenantId);

    /**
     * Same shape as {@link #findByParentFolderForTenant(UUID, TenantId)}, minus its
     * {@code trashedAt IS NULL} predicate (issue #38: {@code FileService#purgeAllInFolder} must
     * purge every File under a Folder regardless of that File's own trashed state).
     *
     * @param parentFolderId the parent Folder's id - never {@code null}
     */
    List<File> findAllByParentFolderForTenant(UUID parentFolderId, TenantId tenantId);

    /**
     * One page of a Folder's Files, ordered by id ascending (UUIDv7 is time-ordered, ADR 0003) -
     * backs the Standalone Service's cursor pagination (ADR 0004), mirroring
     * {@code FolderLookup#findChildrenForTenant(UUID, TenantId, UUID, int)}.
     *
     * @param parentFolderId the parent Folder's id - never {@code null}
     * @param afterId only Files whose id sorts after this one, or {@code null} to start from the
     *     beginning
     * @param limit the maximum number of Files to return
     */
    List<File> findByParentFolderForTenant(UUID parentFolderId, TenantId tenantId, UUID afterId, int limit);

    /**
     * The trash bin's own query (issue #37): Files with their own {@code trashedAt} set - mirrors
     * {@code FolderLookup#findTrashedForTenant} exactly.
     *
     * @param parentFolderId scope to direct children of this Folder, or {@code null} for a flat,
     *     tenant-wide scan (every trashed File regardless of which Folder it's parented under, no
     *     parent filter at all)
     */
    List<File> findTrashedForTenant(UUID parentFolderId, TenantId tenantId);
}
