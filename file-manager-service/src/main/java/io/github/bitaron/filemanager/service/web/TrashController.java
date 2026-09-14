package io.github.bitaron.filemanager.service.web;

import java.util.List;
import java.util.UUID;

import io.github.bitaron.filemanager.api.trash.TrashItemResponse;
import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.folder.Folder;
import io.github.bitaron.filemanager.core.folder.FolderNotFoundException;
import io.github.bitaron.filemanager.core.folder.FolderService;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import io.github.bitaron.filemanager.core.trash.TrashItem;
import io.github.bitaron.filemanager.core.trash.TrashService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Standalone Service's unified trash bin (issue #37): wraps {@link TrashService} over
 * {@code /api/v1/trash}, per ADR 0004's route table. Read-only - no {@code @Transactional} needed
 * (see {@link FolderController}'s own javadoc on why read endpoints don't need it: {@code
 * spring.jpa.open-in-view} already binds a per-request {@code EntityManager} sufficient for
 * reads).
 */
@RestController
@RequestMapping("/api/v1/trash")
@Tag(name = "Trash")
class TrashController {

    private final FolderService folderService;
    private final TrashService trashService;

    /**
     * {@code @Lazy} here mirrors {@link FolderController}'s own {@code FileService} injection
     * point: {@link TrashService} composes {@link FolderService} and {@code core.file.FileService},
     * and {@code FileService} itself transitively needs a {@code StorageBackend} - a plain
     * constructor-injected {@link TrashService} would force Spring to eagerly resolve that whole
     * chain the moment this singleton controller is created during context refresh, defeating the
     * producer-side {@code @Lazy} ({@code TrashAutoConfiguration.trashService}) in hosts that never
     * configured a {@code StorageBackend}.
     */
    TrashController(FolderService folderService, @Lazy TrashService trashService) {
        this.folderService = folderService;
        this.trashService = trashService;
    }

    @GetMapping
    @Operation(
            summary = "List the unified trash bin",
            description = "Omit parentId for a tenant-wide, flat scan of every explicitly-trashed "
                    + "Folder and File. A plain, unpaginated array sorted by trashedAt descending "
                    + "(not CursorPage - issue #37). A trashed parentId Folder is still a valid "
                    + "scope: fetch-by-id doesn't gate on trashed state, so you can browse a "
                    + "trashed Folder's own trash bin.")
    List<TrashItemResponse> list(
            @Parameter(description = "Scope to one Folder's direct trashed children, or omit for tenant-wide")
                    @RequestParam(required = false) UUID parentId,
            TenantId tenantId) {
        if (parentId != null && folderService.fetch(tenantId, parentId) == null) {
            throw new FolderNotFoundException("No Folder with id " + parentId + " exists for this Tenant");
        }

        return trashService.list(tenantId, parentId).stream().map(TrashController::toResponse).toList();
    }

    private static TrashItemResponse toResponse(TrashItem item) {
        return switch (item) {
            case TrashItem.TrashedFolder trashedFolder -> {
                Folder folder = trashedFolder.folder();
                yield new TrashItemResponse(
                        "FOLDER",
                        folder.getId(),
                        folder.getName(),
                        folder.getParentFolderId(),
                        folder.getTrashedAt());
            }
            case TrashItem.TrashedFile trashedFile -> {
                File file = trashedFile.file();
                yield new TrashItemResponse(
                        "FILE", file.getId(), file.getName(), file.getParentFolderId(), file.getTrashedAt());
            }
        };
    }
}
