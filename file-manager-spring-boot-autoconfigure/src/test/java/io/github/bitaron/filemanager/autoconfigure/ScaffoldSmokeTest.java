package io.github.bitaron.filemanager.autoconfigure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placeholder for issue #28 (Multi-module Maven scaffold + CI): proves this module compiles and
 * its test phase runs under the reactor build. The Embedded Mode boot test (see docs/testing.md)
 * lands with the real autoconfigure classes.
 */
class ScaffoldSmokeTest {

    @Test
    void moduleBuildsAndTestsRun() {
        assertThat(true).isTrue();
    }
}
