package io.github.bitaron.filemanager.service.web;

import io.github.bitaron.filemanager.api.file.FileResponse;
import io.github.bitaron.filemanager.core.file.File;

/**
 * Maps {@code file-manager-core}'s {@link File} entity onto its wire shape ({@link FileResponse},
 * in {@code file-manager-api}) - kept out of {@code file-manager-api} itself so that module stays
 * free of a {@code file-manager-core} dependency (its whole point is letting a future v2 thin HTTP
 * client depend on wire shapes without pulling in the engine). Mirrors {@link FolderMapper}
 * exactly; note {@link File#getStorageReference()} has no public getter and is never referenced
 * here - it must never leak onto the wire.
 */
final class FileMapper {

    private FileMapper() {
    }

    static FileResponse toResponse(File file) {
        return new FileResponse(
                file.getId(),
                file.getName(),
                file.getParentFolderId(),
                file.getSize(),
                file.getContentType(),
                file.getVisibility().name(),
                file.getCreatedAt(),
                file.getUpdatedAt());
    }
}
