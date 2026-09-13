package io.github.bitaron.filemanager.api.file;

import java.util.UUID;

/**
 * Documents the wire shape of {@code PATCH /api/v1/files/{id}}'s body (ADR 0004: {@code
 * {name?, parentFolderId?}}) for Swagger. Not bound directly by the controller: JSON's
 * absent-vs-{@code null} distinction on {@code parentFolderId} is meaningful here - a present
 * {@code null} would attempt to move the File to top-level, which {@code FileService.move}
 * rejects (a File can never be top-level, unlike a Folder - ADR 0003) - while an absent key
 * leaves its parent untouched - and a plain record can't tell those two apart on deserialization,
 * so the controller parses the raw request body instead. Mirrors {@code PatchFolderRequest}
 * exactly.
 *
 * @param name the new name, or omit to leave the name unchanged
 * @param parentFolderId the new parent Folder's id, or omit the key entirely to leave the parent
 *     unchanged; an explicit {@code null} is rejected with a {@code 400} rather than moving the
 *     File to top-level
 */
public record PatchFileRequest(String name, UUID parentFolderId) {
}
