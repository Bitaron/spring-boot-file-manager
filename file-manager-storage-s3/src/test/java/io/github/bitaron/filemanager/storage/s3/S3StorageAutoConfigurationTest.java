package io.github.bitaron.filemanager.storage.s3;

import io.github.bitaron.filemanager.storage.StorageBackend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the property gate from ADR 0002: {@link S3StorageAutoConfiguration} contributes a
 * {@link StorageBackend} bean (an {@link S3StorageBackend}) only when
 * {@code file-manager.storage.backend=s3} is set, and contributes nothing otherwise. The seam
 * under test is the {@link org.springframework.context.ApplicationContext} itself - not the
 * autoconfiguration class's internals. No network call is made: building an {@code S3Client}
 * doesn't require reachable credentials or an endpoint, so this stays a fast, offline unit test
 * (the real request path is covered by {@link S3StorageBackendContractTest} against MinIO).
 */
class S3StorageAutoConfigurationTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(S3StorageAutoConfiguration.class));
    }

    @Test
    void registersS3StorageBackendWhenPropertySetToS3() {
        runner()
                .withPropertyValues(
                        "file-manager.storage.backend=s3",
                        "file-manager.storage.s3.bucket=some-bucket",
                        "file-manager.storage.s3.region=us-east-1")
                .run(context -> {
                    assertThat(context).hasSingleBean(StorageBackend.class);
                    assertThat(context.getBean(StorageBackend.class)).isInstanceOf(S3StorageBackend.class);
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
                .withPropertyValues("file-manager.storage.backend=local")
                .run(context -> assertThat(context).doesNotHaveBean(StorageBackend.class));
    }

    @Test
    void failsFastWhenOnlyOneCredentialPropertyIsSet() {
        runner()
                .withPropertyValues(
                        "file-manager.storage.backend=s3",
                        "file-manager.storage.s3.bucket=some-bucket",
                        "file-manager.storage.s3.region=us-east-1",
                        "file-manager.storage.s3.access-key-id=only-one-set")
                .run(context -> assertThatThrownBy(() -> context.getBean(StorageBackend.class))
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasRootCauseMessage(
                                "file-manager.storage.s3.access-key-id and file-manager.storage.s3.secret-access-key"
                                        + " must both be set, or both left unset"));
    }
}
