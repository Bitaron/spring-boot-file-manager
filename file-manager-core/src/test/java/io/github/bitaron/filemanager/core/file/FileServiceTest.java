package io.github.bitaron.filemanager.core.file;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.folder.Folder;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FileService}: no Spring context, no real database for File itself - a
 * fake {@link FileLookup} stands in for {@code FileDao} (docs/testing.md). {@link FolderService}
 * is backed by a real, in-memory-H2 {@link EntityManager} (via its public constructor) rather than
 * a fake {@code FolderLookup}, because that seam is package-private to {@code core.folder} and
 * unreachable from this package - the same hermetic, no-Spring approach
 * {@code FolderServiceTest} itself uses to prove real behavior. {@link StorageBackend} is a tiny
 * in-memory fake.
 */
class FileServiceTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private FolderService folderService;

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
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    void uploadPersistsFileWithExpectedFieldsDefaultsVisibilityToPrivateAndStoresContent() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder folder = folderService.create(tenantId, actor, "Invoices", null);
        entityManager.getTransaction().commit();

        FakeFileLookup fileLookup = new FakeFileLookup();
        FakeStorageBackend storageBackend = new FakeStorageBackend();
        FileService fileService = new FileService(fileLookup, folderService, storageBackend);

        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
        InputStream content = new ByteArrayInputStream(bytes);

        File uploaded = fileService.upload(
                tenantId, actor, folder.getId(), "invoice.pdf", null,
                content, bytes.length, "application/pdf");

        // Expected fields.
        assertThat(uploaded.getName()).isEqualTo("invoice.pdf");
        assertThat(uploaded.getParentFolderId()).isEqualTo(folder.getId());
        assertThat(uploaded.tenantId()).isEqualTo(tenantId);
        assertThat(uploaded.getSize()).isEqualTo(bytes.length);
        assertThat(uploaded.getContentType()).isEqualTo("application/pdf");

        // A null Visibility argument defaults to PRIVATE (acceptance criterion).
        assertThat(uploaded.getVisibility()).isEqualTo(Visibility.PRIVATE);

        // upload() calls through to StorageBackend.store before persisting, and round-trips
        // whatever opaque reference it returns onto the File - never interpreting it (ADR 0002).
        assertThat(storageBackend.storeCalls).hasSize(1);
        FakeStorageBackend.StoreCall storeCall = storageBackend.storeCalls.get(0);
        // The storage key is the File's own generated id, not its (non-unique, ADR 0003) name -
        // two Files named "invoice.pdf" must never collide on the same stored path.
        assertThat(storeCall.key).isEqualTo(uploaded.getId().toString());
        assertThat(storeCall.contentLength).isEqualTo(bytes.length);
        assertThat(storeCall.contentType).isEqualTo("application/pdf");
        assertThat(uploaded.getStorageReference()).isEqualTo(storeCall.returnedReference);

        // Persisted via FileLookup#save, the same instance returned to the caller.
        assertThat(fileLookup.saved).isSameAs(uploaded);
    }

    @Test
    void fetchReturnsFileMetadataByIdForTenant() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File existing = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11, "application/pdf",
                Visibility.PRIVATE, "some/reference", actor, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(existing.getId(), existing);
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        File fetched = fileService.fetch(tenantId, existing.getId());

        assertThat(fetched).isSameAs(existing);
    }

    @Test
    void fetchReturnsNullWhenFileDoesNotResolveForTenant() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        FakeFileLookup fileLookup = new FakeFileLookup();
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        File fetched = fileService.fetch(tenantId, UUID.randomUUID());

        assertThat(fetched).isNull();
    }

    @Test
    void fetchContentReturnsFileAndItsStorageBackendContentStream() throws IOException {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File existing = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11, "application/pdf",
                Visibility.PRIVATE, "some/reference", actor, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(existing.getId(), existing);
        FakeStorageBackend storageBackend = new FakeStorageBackend();
        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
        storageBackend.retrievable.put("some/reference", bytes);
        FileService fileService = new FileService(fileLookup, folderService, storageBackend);

        FileContent fileContent = fileService.fetchContent(tenantId, existing.getId());

        assertThat(fileContent.file()).isSameAs(existing);
        assertThat(fileContent.content().readAllBytes()).isEqualTo(bytes);
    }

    @Test
    void fetchContentThrowsFileNotFoundExceptionWhenFileDoesNotResolveForTenant() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        FakeFileLookup fileLookup = new FakeFileLookup();
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());
        UUID fileId = UUID.randomUUID();

        assertThatThrownBy(() -> fileService.fetchContent(tenantId, fileId))
                .isInstanceOf(FileNotFoundException.class);
    }

    /**
     * The Secure Access Visibility gate (issue #36, the piece PR #60/issue #33 explicitly
     * deferred): {@code CONTEXT.md} - "a Public File is never fetched via a Secure Access call".
     */
    @Test
    void fetchContentThrowsFileNotFoundExceptionWhenFileIsPublic() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File existing = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11, "application/pdf",
                Visibility.PUBLIC, "some/reference", actor, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(existing.getId(), existing);
        // Preloaded so that - absent the gate under test - fetchContent would otherwise succeed
        // and return real content; this keeps the failure unambiguous ("expected a throwable but
        // got a FileContent"), not an incidental fake-storage AssertionError.
        FakeStorageBackend storageBackend = new FakeStorageBackend();
        storageBackend.retrievable.put("some/reference", "hello world".getBytes(StandardCharsets.UTF_8));
        FileService fileService = new FileService(fileLookup, folderService, storageBackend);

        assertThatThrownBy(() -> fileService.fetchContent(tenantId, existing.getId()))
                .isInstanceOf(FileNotFoundException.class);
    }

    /**
     * {@link FileService#fetchContentForNonSecureAccess} is the ungated seam reserved for
     * AccessToken redemption (issue #36) - unlike {@link FileService#fetchContent}, it must return
     * content for a {@code Public} File rather than rejecting it.
     */
    @Test
    void fetchContentForNonSecureAccessReturnsFileAndContentStreamForAPublicFile() throws IOException {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File existing = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11, "application/pdf",
                Visibility.PUBLIC, "some/reference", actor, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(existing.getId(), existing);
        FakeStorageBackend storageBackend = new FakeStorageBackend();
        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
        storageBackend.retrievable.put("some/reference", bytes);
        FileService fileService = new FileService(fileLookup, folderService, storageBackend);

        FileContent fileContent = fileService.fetchContentForNonSecureAccess(tenantId, existing.getId());

        assertThat(fileContent.file()).isSameAs(existing);
        assertThat(fileContent.content().readAllBytes()).isEqualTo(bytes);
    }

    @Test
    void fetchContentForNonSecureAccessThrowsFileNotFoundExceptionWhenFileDoesNotResolveForTenant() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        FakeFileLookup fileLookup = new FakeFileLookup();
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());
        UUID fileId = UUID.randomUUID();

        assertThatThrownBy(() -> fileService.fetchContentForNonSecureAccess(tenantId, fileId))
                .isInstanceOf(FileNotFoundException.class);
    }

    /** Captures what it was asked to save, and answers lookups from a preloaded map. */
    private static final class FakeFileLookup implements FileLookup {
        private final Map<UUID, File> byId = new HashMap<>();
        private File saved;

        @Override
        public File save(File file) {
            this.saved = file;
            return file;
        }

        @Override
        public File findByIdForTenant(UUID id, TenantId tenantId) {
            return byId.get(id);
        }
    }

    @Test
    void uploadDeletesTheJustStoredContentWhenPersistenceFails() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder folder = folderService.create(tenantId, actor, "Invoices", null);
        entityManager.getTransaction().commit();

        FakeStorageBackend storageBackend = new FakeStorageBackend();
        FileLookup failingLookup = new FileLookup() {
            @Override
            public File save(File file) {
                throw new RuntimeException("simulated persistence failure");
            }

            @Override
            public File findByIdForTenant(UUID id, TenantId tenantId) {
                throw new AssertionError("not expected to be called by upload()");
            }
        };
        FileService fileService = new FileService(failingLookup, folderService, storageBackend);
        InputStream content = new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> fileService.upload(
                tenantId, actor, folder.getId(), "invoice.pdf", null, content, 11, "application/pdf"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated persistence failure");

        // The stored content is deleted rather than left orphaned once persistence fails.
        String storedReference = storageBackend.storeCalls.get(0).returnedReference;
        assertThat(storageBackend.deleteCalls).containsExactly(storedReference);
    }

    /**
     * Records every {@link #store}/{@link #delete} call and hands back a deterministic,
     * distinguishable reference; {@link #retrieve} answers from a preloaded map keyed by
     * reference.
     */
    private static final class FakeStorageBackend implements StorageBackend {
        private final java.util.List<StoreCall> storeCalls = new java.util.ArrayList<>();
        private final java.util.List<String> deleteCalls = new java.util.ArrayList<>();
        private final Map<String, byte[]> retrievable = new HashMap<>();

        @Override
        public String store(String key, InputStream content, long contentLength, String contentType) {
            String reference = "fake-reference/" + key;
            storeCalls.add(new StoreCall(key, contentLength, contentType, reference));
            return reference;
        }

        @Override
        public InputStream retrieve(String reference) {
            byte[] bytes = retrievable.get(reference);
            if (bytes == null) {
                throw new AssertionError("no content preloaded for reference " + reference);
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void delete(String reference) {
            deleteCalls.add(reference);
        }

        @Override
        public boolean exists(String reference) {
            throw new AssertionError("not expected to be called by upload()/fetchContent()");
        }

        private record StoreCall(String key, long contentLength, String contentType, String returnedReference) {
        }
    }
}
