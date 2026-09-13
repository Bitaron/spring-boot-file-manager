package io.github.bitaron.filemanager.api.file;

import java.util.UUID;

/**
 * The JSON shape of the {@code metadata} multipart part for {@code POST /api/v1/files} (ADR 0004).
 * {@code visibility} is a raw wire string rather than {@code core.file.Visibility} - this module
 * stays free of a {@code file-manager-core} dependency (mirroring {@code CreateFolderRequest}), so
 * mapping the string onto the enum happens in {@code file-manager-service}. A {@code null} (or
 * omitted) {@code visibility} defaults to Private - see {@code FileService.upload}'s javadoc for
 * where that default is actually applied.
 */
public record UploadFileMetadata(UUID folderId, String name, String visibility) {
}
