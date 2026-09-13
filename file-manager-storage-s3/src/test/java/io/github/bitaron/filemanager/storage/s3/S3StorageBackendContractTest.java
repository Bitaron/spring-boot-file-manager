package io.github.bitaron.filemanager.storage.s3;

import io.github.bitaron.filemanager.storage.StorageBackend;
import io.github.bitaron.filemanager.testsupport.storage.StorageBackendContractTest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;

/**
 * Runs the shared {@link StorageBackendContractTest} suite against {@link S3StorageBackend},
 * wired to a real MinIO instance (Testcontainers) rather than a mock, per this ticket's
 * acceptance criteria and docs/testing.md - the same "no mocking the interface under test" rule
 * {@code LocalStorageBackendContractTest} follows against a real filesystem. Confirms AWS SDK
 * v2's {@code S3Client} works end-to-end against an S3-compatible (non-AWS) target via
 * {@code endpointOverride} + {@code forcePathStyle(true)} (ADR 0002, decision #5).
 */
@Testcontainers
class S3StorageBackendContractTest extends StorageBackendContractTest {

    private static final String BUCKET = "file-manager-contract-test";

    // docker.io/minio/minio was retired by MinIO in favor of quay.io/minio/minio - substituting it
    // in is safe since it's the same image, just republished under a different registry/repo name.
    private static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-04-08T15-41-24Z")
                    .asCompatibleSubstituteFor("minio/minio"));

    private static S3Client s3Client;

    @BeforeAll
    static void startMinioAndCreateBucket() {
        MINIO.start();
        s3Client = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .build();
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void stopMinio() {
        s3Client.close();
        MINIO.stop();
    }

    @Override
    protected StorageBackend backend() {
        return new S3StorageBackend(s3Client, BUCKET);
    }
}
