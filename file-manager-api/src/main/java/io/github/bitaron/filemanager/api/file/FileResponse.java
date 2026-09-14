package io.github.bitaron.filemanager.api.file;

import java.time.Instant;
import java.util.UUID;

/**
 * The wire shape of a File (ADR 0004's DTO field-exposure rule: mirrors entity fields closely,
 * with no {@code tenantId} - a request is already scoped to the caller's own Tenant by ApiKey
 * resolution, so echoing it back would be redundant). Deliberately never includes
 * {@code storageReference} - that field must never leak past {@code core.file} (see
 * {@code core.file.File}'s javadoc), let alone onto the wire. {@code trashedAt} is {@code null}
 * unless this File is trashed (issue #37) - exposed so a trash/restore action's response body
 * actually reflects whether it succeeded.
 */
public record FileResponse(
        UUID id,
        String name,
        UUID parentFolderId,
        long size,
        String contentType,
        String visibility,
        Instant createdAt,
        Instant updatedAt,
        Instant trashedAt) {
}
