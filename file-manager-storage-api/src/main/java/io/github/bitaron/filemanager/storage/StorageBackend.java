package io.github.bitaron.filemanager.storage;

import java.io.InputStream;

/**
 * The seam every storage implementation (local filesystem, S3-compatible, ...) sits behind (ADR
 * 0002). Content flows as {@link InputStream} in both directions - never {@code byte[]}, never
 * Spring's {@code Resource} - so large files never fully materialize in heap and the contract
 * stays framework-neutral. The returned reference is a single opaque {@code String}, persisted
 * as-is on the File entity's {@code storageReference} column; {@code file-manager-core} never
 * interprets it, only round-trips it, so each backend is free to encode its own format (local:
 * relative path; S3: bucket-qualified key).
 */
public interface StorageBackend {

    /**
     * @param key a caller-supplied hint for naming/organizing the stored content (e.g. the
     *     original filename) - a backend may fold it into the returned reference or ignore it
     * @param content the content to store; the caller retains ownership and is responsible for
     *     closing it
     * @param contentLength the exact length of {@code content} in bytes, required up front (e.g.
     *     for S3's single-shot {@code PutObject})
     * @param contentType the content's MIME type
     * @return an opaque reference to the stored content, to be persisted and later passed back to
     *     {@link #retrieve}, {@link #delete}, and {@link #exists}
     */
    String store(String key, InputStream content, long contentLength, String contentType);

    /**
     * @param reference a reference previously returned by {@link #store}
     * @return the stored content; the caller is responsible for closing it
     */
    InputStream retrieve(String reference);

    /**
     * @param reference a reference previously returned by {@link #store}
     */
    void delete(String reference);

    /**
     * @param reference a reference previously returned by {@link #store}, or any other string
     * @return {@code true} if content is currently stored under {@code reference}
     */
    boolean exists(String reference);
}
