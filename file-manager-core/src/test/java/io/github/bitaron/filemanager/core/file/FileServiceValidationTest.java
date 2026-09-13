package io.github.bitaron.filemanager.core.file;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.folder.FolderNotFoundException;
import io.github.bitaron.filemanager.core.folder.FolderService;
import io.github.bitaron.filemanager.core.jpa.TestEntityManagerFactory;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import io.github.bitaron.filemanager.storage.StorageBackend;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests: {@link FileLookup} is faked out with one that rejects any call, so these
 * exercise only {@link FileService#upload}'s own guard-clause validation and its Folder-existence
 * check - no fake used for {@link FolderService} itself, since its own lookup seam is
 * package-private to {@code core.folder} and unreachable from here, mirroring
 * {@code FileServiceTest}'s approach of backing it with a real, in-memory-H2
 * {@link EntityManager} instead of a fake. No database interaction is expected for these guard
 * clauses to trip (docs/testing.md), mirroring {@code FolderServiceValidationTest}.
 */
class FileServiceValidationTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private FolderService folderService;
    private FileService fileService;

    @BeforeAll
    static void openFactory() {
        entityManagerFactory = TestEntityManagerFactory.create();
    }

    @AfterAll
    static void closeFactory() {
        entityManagerFactory.close();
    }

    @BeforeEach
    void openEntityManager() {
        entityManager = entityManagerFactory.createEntityManager();
        folderService = new FolderService(entityManager);
        fileService = new FileService(rejectingLookup(), folderService, rejectingStorageBackend());
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    void rejectsUploadWithNullTenantId() {
        Actor actor = new Actor(UUID.randomUUID());
        InputStream content = emptyContent();

        assertThatThrownBy(() -> fileService.upload(
                null, actor, UUID.randomUUID(), "invoice.pdf", null, content, 0, "application/pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUploadWithNullActor() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        InputStream content = emptyContent();

        assertThatThrownBy(() -> fileService.upload(
                tenantId, null, UUID.randomUUID(), "invoice.pdf", null, content, 0, "application/pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUploadWithNullName() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        InputStream content = emptyContent();

        assertThatThrownBy(() -> fileService.upload(
                tenantId, actor, UUID.randomUUID(), null, null, content, 0, "application/pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUploadWithBlankName() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        InputStream content = emptyContent();

        assertThatThrownBy(() -> fileService.upload(
                tenantId, actor, UUID.randomUUID(), "  ", null, content, 0, "application/pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUploadWithNullContentType() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        InputStream content = emptyContent();

        assertThatThrownBy(() -> fileService.upload(
                tenantId, actor, UUID.randomUUID(), "invoice.pdf", null, content, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUploadWithBlankContentType() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        InputStream content = emptyContent();

        assertThatThrownBy(() -> fileService.upload(
                tenantId, actor, UUID.randomUUID(), "invoice.pdf", null, content, 0, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUploadWithNonExistentFolderId() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        InputStream content = emptyContent();

        assertThatThrownBy(() -> fileService.upload(
                tenantId, actor, UUID.randomUUID(), "invoice.pdf", null, content, 0, "application/pdf"))
                .isInstanceOf(FolderNotFoundException.class);
    }

    @Test
    void rejectsFetchWithNullTenantId() {
        UUID fileId = UUID.randomUUID();

        assertThatThrownBy(() -> fileService.fetch(null, fileId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFetchWithNullFileId() {
        TenantId tenantId = new TenantId(UUID.randomUUID());

        assertThatThrownBy(() -> fileService.fetch(tenantId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFetchContentWithNullTenantId() {
        UUID fileId = UUID.randomUUID();

        assertThatThrownBy(() -> fileService.fetchContent(null, fileId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFetchContentWithNullFileId() {
        TenantId tenantId = new TenantId(UUID.randomUUID());

        assertThatThrownBy(() -> fileService.fetchContent(tenantId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static InputStream emptyContent() {
        return new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
    }

    /** Every guard clause above rejects before {@code upload} would ever reach the DAO. */
    private static FileLookup rejectingLookup() {
        return new FileLookup() {
            @Override
            public File save(File file) {
                throw new AssertionError("A guard-clause rejection must never reach the DAO");
            }

            @Override
            public File findByIdForTenant(UUID id, TenantId tenantId) {
                throw new AssertionError("A guard-clause rejection must never reach the DAO");
            }
        };
    }

    /** Every guard clause above rejects before {@code upload} would ever reach storage. */
    private static StorageBackend rejectingStorageBackend() {
        return new StorageBackend() {
            @Override
            public String store(String key, InputStream content, long contentLength, String contentType) {
                throw new AssertionError("A guard-clause rejection must never reach storage");
            }

            @Override
            public InputStream retrieve(String reference) {
                throw new AssertionError("A guard-clause rejection must never reach storage");
            }

            @Override
            public void delete(String reference) {
                throw new AssertionError("A guard-clause rejection must never reach storage");
            }

            @Override
            public boolean exists(String reference) {
                throw new AssertionError("A guard-clause rejection must never reach storage");
            }
        };
    }
}
