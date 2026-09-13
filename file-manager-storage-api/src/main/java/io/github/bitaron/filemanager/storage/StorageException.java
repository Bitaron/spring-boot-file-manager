package io.github.bitaron.filemanager.storage;

/**
 * Unchecked base for all failures a {@link StorageBackend} implementation may raise (ADR 0002).
 * Kept unchecked so the {@link StorageBackend} contract doesn't force every caller up the stack to
 * declare or catch storage-specific checked exceptions.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
