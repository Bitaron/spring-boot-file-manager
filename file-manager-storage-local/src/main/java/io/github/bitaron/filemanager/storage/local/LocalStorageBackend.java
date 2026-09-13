package io.github.bitaron.filemanager.storage.local;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import io.github.bitaron.filemanager.storage.StorageBackend;
import io.github.bitaron.filemanager.storage.StorageException;
import io.github.bitaron.filemanager.storage.StorageObjectNotFoundException;

/**
 * Local filesystem-backed {@link StorageBackend}, rooted at a directory supplied at construction
 * time. A stored object's reference is the path of its file relative to {@code rootDirectory},
 * using {@code /} as the separator regardless of platform, per ADR 0002's "local: relative path"
 * framing.
 *
 * <p>Every {@code key}/{@code reference} is resolved against {@code rootDirectory} and normalized
 * before use; one that would resolve outside {@code rootDirectory} (e.g. via {@code ..} segments)
 * is rejected rather than silently followed, so a caller-supplied key can never read, write, or
 * delete outside the configured root.
 *
 * <p>{@link #retrieve} and {@link #delete} translate a missing reference (never stored, or already
 * deleted) into {@link StorageObjectNotFoundException}. Any other unexpected I/O failure (from
 * these two, or from {@link #store}) is wrapped in the more general {@link StorageException},
 * per ADR 0002, rather than left as a raw/unchecked {@link IOException}.
 */
public class LocalStorageBackend implements StorageBackend {

    private final Path rootDirectory;

    public LocalStorageBackend(Path rootDirectory) {
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
    }

    @Override
    public String store(String key, InputStream content, long contentLength, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("Failed to store content for key '" + key + "'", e);
        }
        return toReference(target);
    }

    @Override
    public InputStream retrieve(String reference) {
        Path source = resolve(reference);
        try {
            return Files.newInputStream(source);
        } catch (NoSuchFileException e) {
            throw new StorageObjectNotFoundException(
                    "No content stored for reference '" + reference + "'", e);
        } catch (IOException e) {
            throw new StorageException("Failed to retrieve content for reference '" + reference + "'", e);
        }
    }

    @Override
    public void delete(String reference) {
        Path target = resolve(reference);
        try {
            Files.delete(target);
        } catch (NoSuchFileException e) {
            throw new StorageObjectNotFoundException(
                    "No content stored for reference '" + reference + "'", e);
        } catch (IOException e) {
            throw new StorageException("Failed to delete content for reference '" + reference + "'", e);
        }
    }

    @Override
    public boolean exists(String reference) {
        return Files.exists(resolve(reference));
    }

    /**
     * Resolves {@code keyOrReference} against {@link #rootDirectory}, rejecting anything that
     * would escape it (e.g. {@code ../../etc/passwd}) so a caller-supplied string can never reach
     * outside the configured root. A {@code keyOrReference} the filesystem itself rejects (e.g. one
     * containing a NUL byte, surfaced by the JDK as {@link InvalidPathException}) is wrapped in
     * {@link StorageException} rather than left to propagate raw, consistent with every other
     * unexpected failure this backend reports.
     */
    private Path resolve(String keyOrReference) {
        Path resolved;
        try {
            resolved = rootDirectory.resolve(keyOrReference).normalize();
        } catch (InvalidPathException e) {
            throw new StorageException("Not a valid key/reference: '" + keyOrReference + "'", e);
        }
        if (!resolved.startsWith(rootDirectory)) {
            throw new IllegalArgumentException(
                    "key/reference must resolve within rootDirectory, but '" + keyOrReference
                            + "' does not");
        }
        return resolved;
    }

    private String toReference(Path target) {
        return rootDirectory.relativize(target).toString().replace(java.io.File.separatorChar, '/');
    }
}
