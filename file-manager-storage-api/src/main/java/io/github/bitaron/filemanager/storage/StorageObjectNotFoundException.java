package io.github.bitaron.filemanager.storage;

/**
 * Thrown by {@link StorageBackend#retrieve} and {@link StorageBackend#delete} when the given
 * reference doesn't currently have content stored under it - whether it was never stored or was
 * already deleted (ADR 0002).
 */
public class StorageObjectNotFoundException extends StorageException {

    public StorageObjectNotFoundException(String message) {
        super(message);
    }

    public StorageObjectNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
