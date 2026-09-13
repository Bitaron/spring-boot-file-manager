package io.github.bitaron.filemanager.testsupport.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.github.bitaron.filemanager.storage.StorageBackend;
import io.github.bitaron.filemanager.storage.StorageObjectNotFoundException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the {@link StorageBackend} contract itself (docs/adr/0002-storage-backend-contract.md),
 * independent of any one implementation, so every backend module (local filesystem, future S3) is
 * held to the same behavior (docs/testing.md). A concrete subclass supplies a real, wired
 * {@link #backend()} - no mocking the interface under test, matching the "real StorageBackend"
 * rule for anything above the pure-domain layer. Lives in {@code src/main} (not {@code src/test})
 * of this module so a downstream module can extend it from its own test sources.
 *
 * <p>{@link #backend()} and the {@code @Test} methods below are {@code protected} rather than
 * package-private: subclasses live in each backend module's own package, not this one, so plain
 * package-private visibility wouldn't let them see (or JUnit reliably invoke) either.
 *
 * <p>Covers the happy path (store, round-trip via retrieve, exists, delete then exists) and the
 * not-found case: {@link StorageBackend#retrieve} and {@link StorageBackend#delete} must throw
 * {@link StorageObjectNotFoundException} for a reference that was never stored, or was already
 * deleted.
 */
public abstract class StorageBackendContractTest {

    /**
     * @return a {@link StorageBackend} instance under test, freshly wired to its own storage
     *     (e.g. a fresh temp-directory-backed instance) so tests don't interfere with each other
     */
    protected abstract StorageBackend backend();

    @Test
    protected void retrievedContentMatchesWhatWasStored() throws IOException {
        StorageBackend backend = backend();
        byte[] content = "hello, file manager".getBytes(StandardCharsets.UTF_8);

        String reference = backend.store("greeting.txt", new ByteArrayInputStream(content), content.length, "text/plain");

        try (InputStream retrieved = backend.retrieve(reference)) {
            assertThat(retrieved.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    protected void existsIsTrueAfterStoringAndFalseForAnUnknownReference() {
        StorageBackend backend = backend();
        byte[] content = "some content".getBytes(StandardCharsets.UTF_8);

        String reference = backend.store("known.txt", new ByteArrayInputStream(content), content.length, "text/plain");

        assertThat(backend.exists(reference)).isTrue();
        assertThat(backend.exists("no-such-reference")).isFalse();
    }

    @Test
    protected void deletingAStoredObjectMakesExistsReturnFalse() {
        StorageBackend backend = backend();
        byte[] content = "to be deleted".getBytes(StandardCharsets.UTF_8);
        String reference = backend.store("deleteme.txt", new ByteArrayInputStream(content), content.length, "text/plain");

        backend.delete(reference);

        assertThat(backend.exists(reference)).isFalse();
    }

    @Test
    protected void retrievingAReferenceThatWasNeverStoredThrowsNotFound() {
        StorageBackend backend = backend();

        assertThatThrownBy(() -> backend.retrieve("never-stored.txt"))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }

    @Test
    protected void deletingAReferenceThatWasNeverStoredThrowsNotFound() {
        StorageBackend backend = backend();

        assertThatThrownBy(() -> backend.delete("never-stored.txt"))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }

    @Test
    protected void retrievingAnAlreadyDeletedReferenceThrowsNotFound() {
        StorageBackend backend = backend();
        byte[] content = "gone soon".getBytes(StandardCharsets.UTF_8);
        String reference = backend.store("gone.txt", new ByteArrayInputStream(content), content.length, "text/plain");
        backend.delete(reference);

        assertThatThrownBy(() -> backend.retrieve(reference))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }

    @Test
    protected void deletingAnAlreadyDeletedReferenceThrowsNotFound() {
        StorageBackend backend = backend();
        byte[] content = "gone soon".getBytes(StandardCharsets.UTF_8);
        String reference = backend.store("gone-again.txt", new ByteArrayInputStream(content), content.length, "text/plain");
        backend.delete(reference);

        assertThatThrownBy(() -> backend.delete(reference))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }
}
