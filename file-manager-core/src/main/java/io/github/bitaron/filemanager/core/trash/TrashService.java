package io.github.bitaron.filemanager.core.trash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.file.FileService;
import io.github.bitaron.filemanager.core.folder.Folder;
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

    private static Instant trashedAt(TrashItem item) {
        return switch (item) {
            case TrashItem.TrashedFolder trashedFolder -> trashedFolder.folder().getTrashedAt();
            case TrashItem.TrashedFile trashedFile -> trashedFile.file().getTrashedAt();
        };
    }
}
