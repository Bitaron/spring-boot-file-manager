/**
 * S3-compatible {@code StorageBackend} implementation (see issue #35) - AWS SDK v2's
 * {@code S3Client}, confirmed to work against MinIO via {@code endpointOverride} +
 * {@code forcePathStyle(true)}.
 */
package io.github.bitaron.filemanager.storage.s3;
