package io.github.bitaron.filemanager.api.trash;

import java.time.Instant;
import java.util.UUID;

/**
 * The wire shape of one row in {@code GET /api/v1/trash}'s unified listing (issue #37, ADR 0004):
 * a Folder or a File, discriminated by {@code type}. {@code type} is a raw wire string
 * ("FOLDER"/"FILE") rather than a {@code core.trash.TrashItem} sealed-interface reference - this
 * module stays free of a {@code file-manager-core} dependency (mirroring {@code
 * FileResponse.visibility}/{@code MintAccessTokenRequest.purpose}), so discriminating the sealed
 * interface onto this literal happens in {@code file-manager-service}. {@code parentFolderId} is
 * never {@code null} for a {@code FILE} row (every File belongs to exactly one Folder), but can be
 * {@code null} for a top-level, trashed {@code FOLDER} row.
 */
public record TrashItemResponse(String type, UUID id, String name, UUID parentFolderId, Instant trashedAt) {
}
