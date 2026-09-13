package io.github.bitaron.filemanager.api.folder;

import java.util.UUID;

/**
 * Documents the wire shape of {@code PATCH /api/v1/folders/{id}}'s body (ADR 0004: {@code
 * {name?, parentFolderId?}}) for Swagger. Not bound directly by the controller: JSON's
 * absent-vs-{@code null} distinction on {@code parentFolderId} is meaningful here - a present
 * {@code null} moves the Folder to top-level, while an absent key leaves its parent untouched -
 * and a plain record can't tell those two apart on deserialization, so the controller parses the
 * raw request body instead.
 *
 * @param name the new name, or omit to leave the name unchanged
 * @param parentFolderId the new parent Folder's id, {@code null} to move to top-level, or omit
 *     the key entirely to leave the parent unchanged
 */
public record PatchFolderRequest(String name, UUID parentFolderId) {
}
