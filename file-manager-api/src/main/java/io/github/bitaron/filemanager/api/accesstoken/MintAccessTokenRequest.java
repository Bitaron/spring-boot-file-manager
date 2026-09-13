package io.github.bitaron.filemanager.api.accesstoken;

/**
 * Request body for {@code POST /api/v1/files/{id}/access-tokens} (ADR 0004). {@code purpose} is a
 * raw wire string ("VIEW"/"DOWNLOAD") rather than {@code core.accesstoken.Purpose} - this module
 * stays free of a {@code file-manager-core} dependency (mirroring {@code UploadFileMetadata}'s
 * {@code visibility} field), so mapping the string onto the enum happens in
 * {@code file-manager-service}, the same way {@code FileController.parseVisibility} does for
 * {@code UploadFileMetadata.visibility}. {@code ttlSeconds} is optional (a {@code null}/omitted
 * value accepts {@code AccessTokenService.mint}'s own default TTL, still capped at the
 * deployment's per-Purpose ceiling either way - see that method's javadoc).
 */
public record MintAccessTokenRequest(String purpose, Long ttlSeconds) {
}
