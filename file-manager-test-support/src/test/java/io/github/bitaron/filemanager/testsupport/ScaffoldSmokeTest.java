package io.github.bitaron.filemanager.testsupport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placeholder for issue #28 (Multi-module Maven scaffold + CI): proves this module compiles and
 * its test phase runs under the reactor build. The {@code StorageBackend} contract-test suite
 * (issue #32) and shared fixtures land here as later tickets add domain behavior.
 */
class ScaffoldSmokeTest {

    @Test
    void moduleBuildsAndTestsRun() {
        assertThat(true).isTrue();
    }
}
