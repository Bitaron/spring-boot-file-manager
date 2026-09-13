package io.github.bitaron.filemanager.api.folder;

import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/folders} (ADR 0004). {@code parentFolderId} is
 * {@code null} (or omitted) to create a top-level Folder.
 */
public record CreateFolderRequest(String name, UUID parentFolderId) {
}
