package io.github.bitaron.filemanager.core.trash;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.file.FileService;
import io.github.bitaron.filemanager.core.folder.Folder;
import io.github.bitaron.filemanager.core.folder.FolderNotFoundException;
import io.github.bitaron.filemanager.core.folder.FolderService;
import io.github.bitaron.filemanager.core.tenant.TenantId;

/**
 * The unified trash bin (issue #37, decision #17's fourth granular service): a pure read
 * composition over {@link FolderService#listTrashed}/{@link FileService#listTrashed} - no own
 * {@code EntityManager}/DAO, mirroring how {@code core.file.FileService} already composes
 * {@link FolderService}.
 */
public class TrashService {

    private final FolderService folderService;
    private final FileService fileService;

    public TrashService(FolderService folderService, FileService fileService) {
        this.folderService = folderService;
        this.fileService = fileService;
    }

    /**
     * Lists every explicitly-trashed Folder and File together, discriminated by type, sorted by
     * {@code trashedAt} descending (most recently trashed first).
     *
     * @param parentFolderId scope to direct children of this Folder, or {@code null} for a flat,
     *     tenant-wide scan
     */
    public List<TrashItem> list(TenantId tenantId, UUID parentFolderId) {
        List<TrashItem> items = new ArrayList<>();
        for (Folder folder : folderService.listTrashed(tenantId, parentFolderId)) {
            items.add(new TrashItem.TrashedFolder(folder));
        }
        for (File file : fileService.listTrashed(tenantId, parentFolderId)) {
            items.add(new TrashItem.TrashedFile(file));
        }
        items.sort(Comparator.comparing(TrashService::trashedAt).reversed());
        return items;
    }

    /**
     * The Folder-cascade Purge orchestrator (issue #38, decision #16): permanently deletes a
     * trashed Folder and everything beneath it - every descendant Folder's and File's metadata
     * row, plus every descendant File's {@code StorageBackend} content - down an arbitrarily deep
     * subtree (adjacency list, no materialized path, per ADR 0003). This is the cross-aggregate
     * orchestrator {@code FolderService}/{@code FileService} deliberately don't own themselves
     * (issue #37, decision #17's "fourth granular service"): a Folder purge is inherently
     * cross-aggregate, and {@code FolderService} stays File-unaware.
     *
     * <p>Only the root Folder's own {@code trashedAt} is checked - via its own flag, not
     * {@link FolderService#isTrashed}'s ancestor-walk - mirroring how {@code FolderService#restore}
     * only ever looks at a row's own state. Once the root is confirmed trashed, every descendant is
     * purged unconditionally regardless of its own trashed state (decision #16): the subtree is
     * walked via {@link FolderService#listAllChildren}, which - unlike {@link
     * FolderService#listChildren} - has no trashed-exclusion predicate, so the walk reaches every
     * descendant even ones trashed independently, or never trashed at all.
     *
     * <p>The walk itself is breadth-first over an explicit queue rather than recursive: depth is
     * unbounded (ADR 0003) and BFS vs. DFS makes no functional difference here (no ordering
     * requirement, ids are simply collected into a set), so BFS is chosen purely because an
     * explicit queue is simpler to get right than recursion at arbitrary depth. A {@code visited}
     * set guards the walk against a parent-chain cycle, mirroring {@link FolderService#isTrashed}'s
     * own guard: {@link FolderService#move} only rejects a Folder becoming its own *direct* parent,
     * not an indirect cycle (A moved under B, B already under A), so one can exist in the data even
     * though {@code move} tries to prevent it - without this guard, such a cycle would make this
     * queue grow forever instead of terminating. For every collected Folder id (root plus every
     * descendant), {@link FileService#purgeAllInFolder} purges its direct Files first, then
     * {@link FolderService#deleteRow} purges the Folder row itself; the order between those two
     * calls (or between different Folder ids) doesn't matter for correctness, since
     * {@code parentFolderId} is a plain UUID column, not a real FK (no
     * {@code @ManyToOne}/{@code @JoinColumn} anywhere in {@code Folder}/{@code File}), so there's
     * no DB-level ordering constraint.
     *
     * @throws FolderNotFoundException if no Folder with {@code folderId} exists for this Tenant
     * @throws IllegalArgumentException if the Folder's own {@code trashedAt} is {@code null} (not
     *     explicitly trashed)
     */
    public void purge(TenantId tenantId, UUID folderId) {
        Folder root = folderService.fetch(tenantId, folderId);
        if (root == null) {
            throw new FolderNotFoundException("No Folder with id " + folderId + " exists for this Tenant");
        }
        if (root.getTrashedAt() == null) {
            throw new IllegalArgumentException("Folder with id " + folderId + " is not trashed");
        }

        List<UUID> subtreeIds = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(root.getId());
        while (!queue.isEmpty()) {
            UUID currentId = queue.poll();
            if (!visited.add(currentId)) {
                continue;
            }
            subtreeIds.add(currentId);
            for (Folder child : folderService.listAllChildren(tenantId, currentId)) {
                queue.add(child.getId());
            }
        }

        for (UUID id : subtreeIds) {
            fileService.purgeAllInFolder(tenantId, id);
            folderService.deleteRow(tenantId, id);
        }
    }

    private static Instant trashedAt(TrashItem item) {
        return switch (item) {
            case TrashItem.TrashedFolder trashedFolder -> trashedFolder.folder().getTrashedAt();
            case TrashItem.TrashedFile trashedFile -> trashedFile.file().getTrashedAt();
        };
    }
}
