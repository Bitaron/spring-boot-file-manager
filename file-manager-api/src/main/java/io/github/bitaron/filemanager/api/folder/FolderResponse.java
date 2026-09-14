package io.github.bitaron.filemanager.api.folder;

import java.time.Instant;
import java.util.UUID;

/**
 * The wire shape of a Folder (ADR 0004's DTO field-exposure rule: mirrors entity fields closely,
 * with no {@code tenantId} - a request is already scoped to the caller's own Tenant by ApiKey
 * resolution, so echoing it back would be redundant). {@code parentFolderId} is {@code null} for
 * a top-level Folder. {@code trashedAt} is {@code null} unless this Folder is trashed (issue #37)
 * - exposed so a trash/restore action's response body actually reflects whether it succeeded.
 */
public record FolderResponse(
        UUID id, String name, UUID parentFolderId, Instant createdAt, Instant updatedAt, Instant trashedAt) {
}
