package io.github.bitaron.filemanager.storage.local;

import java.nio.file.Path;

import io.github.bitaron.filemanager.storage.StorageBackend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the property gate from ADR 0002: {@link LocalStorageAutoConfiguration} contributes a
 * {@link StorageBackend} bean (a {@link LocalStorageBackend}) only when
 * {@code file-manager.storage.backend=local} is set, and contributes nothing otherwise. The seam
 * under test is the {@link org.springframework.context.ApplicationContext} itself - not the
 * autoconfiguration class's internals.
 */
class LocalStorageAutoConfigurationTest {

    @TempDir
    Path tempDir;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LocalStorageAutoConfiguration.class));
    }

    @Test
    void registersLocalStorageBackendWhenPropertySetToLocal() {
        runner()
                .withPropertyValues(
                        "file-manager.storage.backend=local",
                        "file-manager.storage.local.root-directory=" + tempDir)
                .run(context -> {
                    assertThat(context).hasSingleBean(StorageBackend.class);
                    assertThat(context.getBean(StorageBackend.class))
                            .isInstanceOf(LocalStorageBackend.class);
                });
    }

    @Test
    void registersNoStorageBackendWhenPropertyAbsent() {
        runner()
                .run(context -> assertThat(context).doesNotHaveBean(StorageBackend.class));
    }

    @Test
    void registersNoStorageBackendWhenPropertySetToDifferentBackend() {
        runner()
                .withPropertyValues("file-manager.storage.backend=s3")
                .run(context -> assertThat(context).doesNotHaveBean(StorageBackend.class));
    }
}
