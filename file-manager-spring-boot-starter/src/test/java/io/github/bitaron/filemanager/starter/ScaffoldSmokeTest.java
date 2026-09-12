package io.github.bitaron.filemanager.starter;

import io.github.bitaron.filemanager.autoconfigure.FileManagerAutoconfigureMarker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placeholder for issue #28 (Multi-module Maven scaffold + CI): proves this starter module
 * compiles, its test phase runs, and it transitively carries
 * {@code file-manager-spring-boot-autoconfigure} onto the classpath - the starter's whole job.
 */
class ScaffoldSmokeTest {

    @Test
    void starterCarriesAutoconfigureOntoTheClasspath() {
        assertThat(FileManagerAutoconfigureMarker.class).isNotNull();
    }
}
