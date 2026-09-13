package io.github.bitaron.filemanager.storage.s3;

import java.io.InputStream;

import io.github.bitaron.filemanager.storage.StorageBackend;
import io.github.bitaron.filemanager.storage.StorageException;
import io.github.bitaron.filemanager.storage.StorageObjectNotFoundException;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3-compatible {@link StorageBackend}, targeting a single bucket configured at construction time
 * (ADR 0002, decision #5). A stored object's reference is the S3 key itself, unchanged from the
 * {@code key} passed to {@link #store} - the bucket is fixed per backend instance, so it needs no
 * encoding in the reference. Works against real AWS S3 or any S3-compatible target (e.g. MinIO via
 * {@code endpointOverride} + {@code forcePathStyle(true)} on the supplied {@link S3Client}).
 *
 * <p>S3's {@code DeleteObject} is idempotent and succeeds even when the key doesn't exist, unlike
 * this contract's delete-must-404-on-missing rule (ADR 0002) - so {@link #delete} checks
 * {@link #exists} first and raises {@link StorageObjectNotFoundException} itself rather than
 * relying on S3 to reject the call.
 */
public class S3StorageBackend implements StorageBackend {

    private final S3Client s3Client;
    private final String bucket;

    public S3StorageBackend(S3Client s3Client, String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public String store(String key, InputStream content, long contentLength, String contentType) {
        try {
            // contentLength is supplied only via RequestBody below, not also on the request
            // itself - the SDK falls back to RequestBody#optionalContentLength for the
            // Content-Length header when the request field is unset, so setting both would just
            // duplicate the same value in two places.
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromInputStream(content, contentLength));
        } catch (SdkException e) {
            throw new StorageException("Failed to store content for key '" + key + "'", e);
        }
        return key;
    }

    @Override
    public InputStream retrieve(String reference) {
        try {
            return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(reference).build());
        } catch (NoSuchKeyException e) {
            throw new StorageObjectNotFoundException(
                    "No content stored for reference '" + reference + "'", e);
        } catch (SdkException e) {
            throw new StorageException("Failed to retrieve content for reference '" + reference + "'", e);
        }
    }

    @Override
    public void delete(String reference) {
        if (!exists(reference)) {
            throw new StorageObjectNotFoundException("No content stored for reference '" + reference + "'");
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(reference).build());
        } catch (SdkException e) {
            throw new StorageException("Failed to delete content for reference '" + reference + "'", e);
        }
    }

    @Override
    public boolean exists(String reference) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(reference).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // HeadObject returns no body on a 404, so the SDK can't always unmarshal it to
            // NoSuchKeyException (aws/aws-sdk-java-v2#1941) - the status code is the reliable signal.
            if (e.statusCode() == 404) {
                return false;
            }
            throw new StorageException("Failed to check existence for reference '" + reference + "'", e);
        } catch (SdkException e) {
            throw new StorageException("Failed to check existence for reference '" + reference + "'", e);
        }
    }

    /**
     * Closes the underlying {@link S3Client}. Named {@code close} (rather than implementing
     * {@link AutoCloseable} on this class, which the {@link StorageBackend} contract doesn't
     * expect callers to know about) purely so Spring's inferred-destroy-method support picks it up
     * automatically when this backend is registered as a bean (see {@link S3StorageAutoConfiguration}).
     */
    public void close() {
        s3Client.close();
    }
}
