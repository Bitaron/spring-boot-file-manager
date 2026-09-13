package io.github.bitaron.filemanager.storage.local;

import java.nio.file.Path;

import io.github.bitaron.filemanager.storage.StorageBackend;
import io.github.bitaron.filemanager.testsupport.storage.StorageBackendContractTest;

import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the shared {@link StorageBackendContractTest} suite against {@link LocalStorageBackend}
 * rooted at a real, throwaway directory (JUnit {@code @TempDir}) - no mocking the filesystem
 * (docs/testing.md).
 */
class LocalStorageBackendContractTest extends StorageBackendContractTest {

    @TempDir
    Path tempDir;

    @Override
    protected StorageBackend backend() {
        return new LocalStorageBackend(tempDir);
    }
}
