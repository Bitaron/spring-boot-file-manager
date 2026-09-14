package io.github.bitaron.filemanager.service.web;

import io.github.bitaron.filemanager.api.folder.FolderResponse;
import io.github.bitaron.filemanager.core.folder.Folder;

/**
 * Maps {@code file-manager-core}'s {@link Folder} entity onto its wire shape ({@link
 * FolderResponse}, in {@code file-manager-api}) - kept out of {@code file-manager-api} itself so
 * that module stays free of a {@code file-manager-core} dependency (its whole point is letting a
 * future v2 thin HTTP client depend on wire shapes without pulling in the engine).
 */
final class FolderMapper {

    private FolderMapper() {
    }

    static FolderResponse toResponse(Folder folder) {
        return new FolderResponse(
                folder.getId(),
                folder.getName(),
                folder.getParentFolderId(),
                folder.getCreatedAt(),
                folder.getUpdatedAt(),
                folder.getTrashedAt());
    }
}
