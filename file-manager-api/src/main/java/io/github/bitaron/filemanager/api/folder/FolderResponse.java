package io.github.bitaron.filemanager.api.folder;

import java.time.Instant;
import java.util.UUID;

/**
 * The wire shape of a Folder (ADR 0004's DTO field-exposure rule: mirrors entity fields closely,
 * with no {@code tenantId} - a request is already scoped to the caller's own Tenant by ApiKey
 * resolution, so echoing it back would be redundant). {@code parentFolderId} is {@code null} for
 * a top-level Folder.
 */
public record FolderResponse(UUID id, String name, UUID parentFolderId, Instant createdAt, Instant updatedAt) {
}
