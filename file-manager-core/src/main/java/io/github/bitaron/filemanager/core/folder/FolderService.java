package io.github.bitaron.filemanager.core.folder;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
     *     missing/blank
     * @throws FolderNotFoundException if {@code parentFolderId} is non-null and no Folder with
     *     that id exists for this Tenant - callers get a clean, catchable rejection instead of a
     *     raw persistence-provider exception surfacing at commit time
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
            throw new FolderNotFoundException(
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
     * Lists a Folder's immediate children, scoped to {@code tenantId}. Excludes children with
     * their own {@code trashedAt} set, and returns empty entirely if {@code parentFolderId} is
     * non-null and itself effectively trashed (issue #37, decision #16) - this is what makes an
     * entire trashed subtree disappear from listings with no per-descendant write.
     *
     * @param parentFolderId the parent Folder's id, or {@code null} to list top-level Folders
     */
    public List<Folder> listChildren(TenantId tenantId, UUID parentFolderId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (parentFolderId != null && isTrashed(tenantId, parentFolderId)) {
            return List.of();
        }
        return folderLookup.findChildrenForTenant(parentFolderId, tenantId);
    }

    /**
     * Lists a Folder's immediate children one page at a time, scoped to {@code tenantId} - the
     * seam the Standalone Service's cursor pagination is built on (ADR 0004: cursor pagination
     * over the already-time-ordered UUIDv7 PK). Callers ask for one extra row past {@code limit}
     * to learn whether a next page exists, the same convention {@code afterId} feeds back in as
     * the next request's cursor.
     *
     * <p>Unlike {@link #create}/{@link #rename}/{@link #move}, this never validates that
     * {@code parentFolderId} itself exists for this Tenant - a nonexistent or foreign
     * {@code parentFolderId} simply yields an empty page, the same as an existing, childless one.
     * Distinguishing those (a {@code 404} for the REST layer's non-enumerability rule, ADR 0004)
     * is a REST-layer concern precisely because it doesn't apply to every caller: an Embedded Mode
     * caller with its own already-validated {@code parentFolderId} has no need for the extra
     * lookup. {@code FolderController} performs that existence check itself before calling this.
     *
     * <p>Same trash-exclusion rule as {@link #listChildren(TenantId, UUID)} (issue #37, decision
     * #16): children with their own {@code trashedAt} set are excluded, and the page is empty
     * entirely if {@code parentFolderId} is non-null and itself effectively trashed.
     *
     * @param parentFolderId the parent Folder's id, or {@code null} to list top-level Folders
     * @param afterId list Folders whose id sorts after this one, or {@code null} to start from
     *     the beginning
     * @param limit the maximum number of Folders to return
     * @throws IllegalArgumentException if {@code tenantId} is missing or {@code limit} is not
     *     positive
     */
    public List<Folder> listChildren(TenantId tenantId, UUID parentFolderId, UUID afterId, int limit) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (parentFolderId != null && isTrashed(tenantId, parentFolderId)) {
            return List.of();
        }
        return folderLookup.findChildrenForTenant(parentFolderId, tenantId, afterId, limit);
    }

    /**
     * Lists a Folder's immediate children, scoped to {@code tenantId} - <b>without</b>
     * {@link #listChildren}'s trashed-exclusion rule. This exists specifically for the Purge
     * cascade walk (issue #38, {@code core.trash.TrashService#purge}): that walk must reach a
     * descendant even if only an ancestor - not that descendant itself - is trashed, since
     * descendants are purged unconditionally once the root is confirmed trashed (decision #16).
     *
     * @param parentFolderId the parent Folder's id, or {@code null} to list top-level Folders
     * @throws IllegalArgumentException if {@code tenantId} is missing
     */
    public List<Folder> listAllChildren(TenantId tenantId, UUID parentFolderId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        return folderLookup.findAllChildrenForTenant(parentFolderId, tenantId);
    }

    /**
     * Permanently deletes a single Folder's metadata row (issue #38: Purge) - unconditionally, with
     * <b>no</b> trashed-state check. Unlike {@link #trash}/{@link #restore}, this performs no
     * validation of its own beyond existence: the Purge cascade ({@code core.trash.TrashService})
     * validates the *root* Folder's own {@code trashedAt} exactly once, then purges every
     * descendant regardless of that descendant's own trashed state (decision #16) - re-checking it
     * here per descendant would be redundant, not protective. {@code FolderService} stays
     * File-unaware (docs/architecture.md), so this deletes only the Folder row; the cascade
     * orchestrator is responsible for also purging any Files the Folder contains.
     *
     * @throws IllegalArgumentException if {@code tenantId} or {@code folderId} is missing
     * @throws FolderNotFoundException if no Folder with {@code folderId} exists for this Tenant
     */
    public void deleteRow(TenantId tenantId, UUID folderId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (folderId == null) {
            throw new IllegalArgumentException("folderId must not be null");
        }
        Folder folder = folderLookup.findByIdForTenant(folderId, tenantId);
        if (folder == null) {
            throw new FolderNotFoundException("No Folder with id " + folderId + " exists for this Tenant");
        }
        folderLookup.delete(folder);
    }

    /**
     * Renames a Folder in place - a single-row update (ADR 0003/0004). Duplicate sibling names
     * stay legal (ADR 0003), so no uniqueness check is performed.
     *
     * @throws IllegalArgumentException if {@code tenantId}, {@code actor}, or {@code folderId} is
     *     missing, or {@code name} is missing/blank
     * @throws FolderNotFoundException if no Folder with {@code folderId} exists for this Tenant
     */
    public Folder rename(TenantId tenantId, Actor actor, UUID folderId, String name) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (folderId == null) {
            throw new IllegalArgumentException("folderId must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        Folder folder = folderLookup.findByIdForTenant(folderId, tenantId);
        if (folder == null) {
            throw new FolderNotFoundException("No Folder with id " + folderId + " exists for this Tenant");
        }
        folder.rename(name, actor, Instant.now());
        return folderLookup.save(folder);
    }

    /**
     * Moves a Folder to a different parent, or to top-level - a single-row update (ADR 0003/0004).
     *
     * @param parentFolderId the new parent Folder's id, or {@code null} to move to top-level
     * @throws IllegalArgumentException if {@code tenantId}, {@code actor}, or {@code folderId} is
     *     missing, or {@code parentFolderId} equals {@code folderId} (a Folder can't be its own
     *     parent - ADR 0003's ancestor-trashed check walks the parent chain, and a
     *     self-referencing row never terminates that walk)
     * @throws FolderNotFoundException if no Folder with {@code folderId} exists for this Tenant,
     *     or {@code parentFolderId} is non-null and no Folder with that id exists for this Tenant
     */
    public Folder move(TenantId tenantId, Actor actor, UUID folderId, UUID parentFolderId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (folderId == null) {
            throw new IllegalArgumentException("folderId must not be null");
        }
        if (folderId.equals(parentFolderId)) {
            throw new IllegalArgumentException("A Folder cannot be moved into itself");
        }
        Folder folder = folderLookup.findByIdForTenant(folderId, tenantId);
        if (folder == null) {
            throw new FolderNotFoundException("No Folder with id " + folderId + " exists for this Tenant");
        }
        if (parentFolderId != null && folderLookup.findByIdForTenant(parentFolderId, tenantId) == null) {
            throw new FolderNotFoundException(
                    "No Folder with id " + parentFolderId + " exists for this Tenant");
        }
        folder.moveTo(parentFolderId, actor, Instant.now());
        return folderLookup.save(folder);
    }

    /**
     * Trashes a Folder in place - a single-row update (ADR 0003/0004, decision #16). Descendants
     * are never touched; their "effectively trashed" state is computed at read time instead.
     *
     * @throws IllegalArgumentException if {@code tenantId}, {@code actor}, or {@code folderId} is
     *     missing
     * @throws FolderNotFoundException if no Folder with {@code folderId} exists for this Tenant
     */
    public Folder trash(TenantId tenantId, Actor actor, UUID folderId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (folderId == null) {
            throw new IllegalArgumentException("folderId must not be null");
        }
        Folder folder = folderLookup.findByIdForTenant(folderId, tenantId);
        if (folder == null) {
            throw new FolderNotFoundException("No Folder with id " + folderId + " exists for this Tenant");
        }
        folder.trash(actor, Instant.now());
        return folderLookup.save(folder);
    }

    /**
     * Restores a trashed Folder in place - a single-row update (ADR 0003/0004, decision #16).
     * Only this Folder's own trashed state is cleared; ancestor state is never checked or
     * touched, so a descendant trashed independently of an ancestor stays trashed after that
     * ancestor is restored.
     *
     * @throws IllegalArgumentException if {@code tenantId}, {@code actor}, or {@code folderId} is
     *     missing
     * @throws FolderNotFoundException if no Folder with {@code folderId} exists for this Tenant
     */
    public Folder restore(TenantId tenantId, Actor actor, UUID folderId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (folderId == null) {
            throw new IllegalArgumentException("folderId must not be null");
        }
        Folder folder = folderLookup.findByIdForTenant(folderId, tenantId);
        if (folder == null) {
            throw new FolderNotFoundException("No Folder with id " + folderId + " exists for this Tenant");
        }
        folder.restore(actor, Instant.now());
        return folderLookup.save(folder);
    }

    /**
     * Whether a Folder is "effectively trashed" (issue #37, decision #16/ADR 0003): its own
     * {@code trashedAt} is set, or any ancestor Folder's is. Walks the parent chain at read time
     * via repeated lookups - no materialized/cached state, per ADR 0003. {@code public} because
     * {@code core.file.FileService} needs to consult a File's parent Folder chain too.
     *
     * <p>A nonexistent {@code folderId} (for this Tenant) is treated as not trashed - callers
     * that need existence checked (e.g. a listing's own {@code parentFolderId} validation) do
     * that separately; this method only answers the trashed question.
     */
    public boolean isTrashed(TenantId tenantId, UUID folderId) {
        return isTrashed(tenantId, folderId, new HashSet<>());
    }

    /**
     * The walk itself, guarded against a parent-chain cycle. {@code move} only rejects a Folder
     * becoming its own direct parent, not an indirect cycle (A moved under B, B already under A)
     * - {@code visited} stops this walk from recursing forever if such a cycle exists, rather than
     * relying on {@code move}'s validation to have prevented one. A repeated id is treated as "not
     * (further) trashed" - the walk has already checked every Folder it can reach without looping,
     * so there's nothing more to learn by continuing.
     */
    private boolean isTrashed(TenantId tenantId, UUID folderId, Set<UUID> visited) {
        if (!visited.add(folderId)) {
            return false;
        }
        Folder folder = folderLookup.findByIdForTenant(folderId, tenantId);
        if (folder == null) {
            return false;
        }
        if (folder.getTrashedAt() != null) {
            return true;
        }
        UUID parentFolderId = folder.getParentFolderId();
        if (parentFolderId == null) {
            return false;
        }
        return isTrashed(tenantId, parentFolderId, visited);
    }

    /**
     * The trash bin's own query (issue #37): Folders with their own {@code trashedAt} set - unlike
     * {@link #listChildren}'s exclusion rule, this surfaces only explicitly-trashed items, not the
     * ancestor-computed "effectively trashed" state.
     *
     * @param parentFolderId scope to direct children of this Folder, or {@code null} for a flat,
     *     tenant-wide scan (every trashed Folder regardless of nesting depth, no parent filter at
     *     all)
     * @throws IllegalArgumentException if {@code tenantId} is missing
     */
    public List<Folder> listTrashed(TenantId tenantId, UUID parentFolderId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        return folderLookup.findTrashedForTenant(parentFolderId, tenantId);
    }
}
