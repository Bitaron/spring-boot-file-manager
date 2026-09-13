package io.github.bitaron.filemanager.core.folder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.EntityManager;

/**
 * Domain operations on {@link Folder} (create/move/trash - docs/architecture.md's "service
 * interfaces, not entity setters" rule). Delegates persistence mechanics to a {@link FolderLookup},
 * keeping this class free to grow business rules (e.g. the rename/move validation planned for a
 * later cycle) without also owning {@link EntityManager} plumbing, and letting its own
 * guard-clause tests fake that seam out with no database (docs/testing.md), mirroring apikey's
 * {@code ApiKeyResolver}/{@code ApiKeyLookup} seam.
 */
public class FolderService {

    private final FolderLookup folderLookup;

    public FolderService(EntityManager entityManager) {
        this(new FolderDao(entityManager));
    }

    FolderService(FolderLookup folderLookup) {
        this.folderLookup = folderLookup;
    }

    /**
     * Creates a new Folder for the given Tenant, owned by {@code actor}.
     *
     * @param parentFolderId the parent Folder's id, or {@code null} to create a top-level Folder
     * @throws IllegalArgumentException if {@code tenantId}, {@code actor}, or {@code name} is
     *     missing/blank, or if {@code parentFolderId} is non-null and no Folder with that id
     *     exists for this Tenant - callers get a clean, catchable rejection instead of a raw
     *     persistence-provider exception surfacing at commit time
     */
    public Folder create(TenantId tenantId, Actor actor, String name, UUID parentFolderId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        if (parentFolderId != null && folderLookup.findByIdForTenant(parentFolderId, tenantId) == null) {
            throw new IllegalArgumentException(
                    "No Folder with id " + parentFolderId + " exists for this Tenant");
        }
        Folder folder = new Folder(tenantId, parentFolderId, name, actor, Instant.now());
        return folderLookup.save(folder);
    }

    /**
     * Fetches a single Folder's metadata by id, scoped to {@code tenantId}.
     *
     * @return the Folder, or {@code null} if no Folder with that id exists for this Tenant
     */
    public Folder fetch(TenantId tenantId, UUID folderId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (folderId == null) {
            throw new IllegalArgumentException("folderId must not be null");
        }
        return folderLookup.findByIdForTenant(folderId, tenantId);
    }

    /**
     * Lists a Folder's immediate children, scoped to {@code tenantId}.
     *
     * @param parentFolderId the parent Folder's id, or {@code null} to list top-level Folders
     */
    public List<Folder> listChildren(TenantId tenantId, UUID parentFolderId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        return folderLookup.findChildrenForTenant(parentFolderId, tenantId);
    }
}
