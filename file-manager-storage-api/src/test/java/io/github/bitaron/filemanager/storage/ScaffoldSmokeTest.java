package io.github.bitaron.filemanager.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placeholder for issue #28 (Multi-module Maven scaffold + CI): proves this module compiles and
 * its test phase runs under the reactor build. The {@code StorageBackend} contract-test suite
 * (issue #32) lives in {@code file-manager-test-support}, not here.
 */
class ScaffoldSmokeTest {

    @Test
    void moduleBuildsAndTestsRun() {
        assertThat(true).isTrue();
    }
}
