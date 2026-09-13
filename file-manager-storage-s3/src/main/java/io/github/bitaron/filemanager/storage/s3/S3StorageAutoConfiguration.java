package io.github.bitaron.filemanager.storage.s3;

import java.net.URI;

import io.github.bitaron.filemanager.storage.StorageBackend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.Nullable;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Embedded Mode wiring for the S3-compatible {@link S3StorageBackend}, guarded so it only
 * activates when the host opts into it via {@code file-manager.storage.backend=s3} (ADR 0002).
 * Self-registered via this module's own
 * {@code src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * - deliberately NOT registered from {@code file-manager-spring-boot-autoconfigure}, which never
 * compiles against a concrete backend, mirroring {@code LocalStorageAutoConfiguration}.
 *
 * <p>{@code region} is required (not defaulted) so bean creation never falls through to the AWS
 * SDK's ambient region-provider chain, which throws if no region is configured anywhere in the
 * environment - a surprise this autoconfiguration avoids by always requiring it explicitly, the
 * same way {@code LocalStorageAutoConfiguration} always requires {@code root-directory}.
 * {@code endpoint} and the static credential pair are optional and only needed to target a
 * non-AWS S3-compatible service (e.g. MinIO, via {@code endpoint} + the forced path-style below);
 * against real AWS S3, region plus the ambient {@code DefaultCredentialsProvider} chain suffice.
 * The credential pair must be set together or not at all - setting only one is rejected rather
 * than silently falling back to the ambient chain, which could otherwise authenticate against the
 * wrong account/target without any indication of the misconfiguration.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "file-manager.storage.backend", havingValue = "s3")
public class S3StorageAutoConfiguration {

    /**
     * {@code @ConditionalOnMissingBean(StorageBackend.class)} mirrors
     * {@code LocalStorageAutoConfiguration}'s use of the same pattern, so a host can still supply
     * its own {@link StorageBackend} bean to override this one.
     */
    @Bean
    @ConditionalOnMissingBean(StorageBackend.class)
    public StorageBackend s3StorageBackend(
            @Value("${file-manager.storage.s3.bucket}") String bucket,
            @Value("${file-manager.storage.s3.region}") String region,
            @Value("${file-manager.storage.s3.endpoint:#{null}}") @Nullable String endpoint,
            @Value("${file-manager.storage.s3.access-key-id:#{null}}") @Nullable String accessKeyId,
            @Value("${file-manager.storage.s3.secret-access-key:#{null}}") @Nullable String secretAccessKey) {
        if (accessKeyId == null != (secretAccessKey == null)) {
            throw new IllegalStateException(
                    "file-manager.storage.s3.access-key-id and file-manager.storage.s3.secret-access-key"
                            + " must both be set, or both left unset");
        }
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
        if (endpoint != null) {
            // forcePathStyle is required for MinIO (and most other S3-compatible targets), which
            // don't support AWS S3's default virtual-hosted-style addressing (ADR 0002, decision #5).
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        if (accessKeyId != null) {
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        }
        return new S3StorageBackend(builder.build(), bucket);
    }
}
