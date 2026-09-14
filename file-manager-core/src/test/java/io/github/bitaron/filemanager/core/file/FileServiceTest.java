package io.github.bitaron.filemanager.core.file;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.folder.Folder;
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

    @Test
    void renameUpdatesNameBumpsUpdatedAtAndLastModifiedByAndPersistsViaFileLookup() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor creator = new Actor(UUID.randomUUID());
        Actor renamer = new Actor(UUID.randomUUID());
        File existing = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", creator, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(existing.getId(), existing);
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        File renamed = fileService.rename(tenantId, renamer, existing.getId(), "Invoice Final.pdf");

        assertThat(renamed.getName()).isEqualTo("Invoice Final.pdf");
        assertThat(renamed.lastModifiedBy()).isEqualTo(renamer);
        assertThat(renamed.getUpdatedAt()).isAfterOrEqualTo(renamed.getCreatedAt());
        assertThat(fileLookup.saved).isSameAs(existing);
    }

    @Test
    void moveUpdatesParentFolderIdAndPersistsViaFileLookup() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder destination = folderService.create(tenantId, actor, "Archive", null);
        entityManager.getTransaction().commit();

        File existing = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", actor, Instant.now());
        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(existing.getId(), existing);
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        File moved = fileService.move(tenantId, actor, existing.getId(), destination.getId());

        assertThat(moved.getParentFolderId()).isEqualTo(destination.getId());
        assertThat(fileLookup.saved).isSameAs(existing);
    }

    @Test
    void moveToANonExistentFolderThrowsFolderNotFoundException() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File existing = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", actor, Instant.now());
        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(existing.getId(), existing);
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        assertThatThrownBy(() -> fileService.move(tenantId, actor, existing.getId(), UUID.randomUUID()))
                .isInstanceOf(FolderNotFoundException.class);
    }

    @Test
    void moveOfANonExistentFileThrowsFileNotFoundException() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        FakeFileLookup fileLookup = new FakeFileLookup();
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        assertThatThrownBy(() -> fileService.move(tenantId, actor, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void trashSetsTheFilesOwnTrashedAtAndTrashedBy() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor creator = new Actor(UUID.randomUUID());
        Actor trasher = new Actor(UUID.randomUUID());
        File existing = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", creator, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(existing.getId(), existing);
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        File trashed = fileService.trash(tenantId, trasher, existing.getId());

        assertThat(trashed.getTrashedAt()).isNotNull();
        assertThat(trashed.trashedBy()).isEqualTo(trasher);
        assertThat(fileLookup.saved).isSameAs(existing);
    }

    /**
     * Trashing an already-trashed File must be a full no-op (issue #37, mirroring
     * {@code FolderServiceTest#trashingAnAlreadyTrashedFolderIsANoOp}): the second call, made by a
     * different Actor, must not overwrite the first call's {@code trashedAt}/{@code trashedBy}, nor
     * bump {@code updatedAt}/{@code lastModifiedBy}.
     */
    @Test
    void trashingAnAlreadyTrashedFileIsANoOp() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor creator = new Actor(UUID.randomUUID());
        Actor firstTrasher = new Actor(UUID.randomUUID());
        Actor secondTrasher = new Actor(UUID.randomUUID());
        File existing = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", creator, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(existing.getId(), existing);
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        fileService.trash(tenantId, firstTrasher, existing.getId());
        Instant trashedAtAfterFirstTrash = existing.getTrashedAt();
        Instant updatedAtAfterFirstTrash = existing.getUpdatedAt();
        Actor lastModifiedByAfterFirstTrash = existing.lastModifiedBy();

        File afterSecondTrash = fileService.trash(tenantId, secondTrasher, existing.getId());

        assertThat(afterSecondTrash.getTrashedAt()).isEqualTo(trashedAtAfterFirstTrash);
        assertThat(afterSecondTrash.trashedBy()).isEqualTo(firstTrasher);
        assertThat(afterSecondTrash.getUpdatedAt()).isEqualTo(updatedAtAfterFirstTrash);
        assertThat(afterSecondTrash.lastModifiedBy()).isEqualTo(lastModifiedByAfterFirstTrash);
    }

    @Test
    void restoreClearsTheFilesOwnTrashedState() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor creator = new Actor(UUID.randomUUID());
        Actor trasher = new Actor(UUID.randomUUID());
        Actor restorer = new Actor(UUID.randomUUID());
        File existing = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", creator, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(existing.getId(), existing);
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        fileService.trash(tenantId, trasher, existing.getId());
        File restored = fileService.restore(tenantId, restorer, existing.getId());

        assertThat(restored.getTrashedAt()).isNull();
        assertThat(restored.trashedBy()).isNull();
    }

    /**
     * Restoring an already-active (never-trashed) File must be a full no-op (issue #37, symmetric
     * with {@link #trashingAnAlreadyTrashedFileIsANoOp}): nothing changes, including
     * {@code updatedAt}/{@code lastModifiedBy}.
     */
    @Test
    void restoringAnAlreadyActiveFileIsANoOp() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor creator = new Actor(UUID.randomUUID());
        Actor restorer = new Actor(UUID.randomUUID());
        File existing = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", creator, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(existing.getId(), existing);
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());
        Instant updatedAtBeforeRestore = existing.getUpdatedAt();
        Actor lastModifiedByBeforeRestore = existing.lastModifiedBy();

        File afterRestore = fileService.restore(tenantId, restorer, existing.getId());

        assertThat(afterRestore.getTrashedAt()).isNull();
        assertThat(afterRestore.trashedBy()).isNull();
        assertThat(afterRestore.getUpdatedAt()).isEqualTo(updatedAtBeforeRestore);
        assertThat(afterRestore.lastModifiedBy()).isEqualTo(lastModifiedByBeforeRestore);
    }

    @Test
    void trashOfANonExistentFileThrowsFileNotFoundException() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        FakeFileLookup fileLookup = new FakeFileLookup();
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        assertThatThrownBy(() -> fileService.trash(tenantId, actor, UUID.randomUUID()))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void restoreOfANonExistentFileThrowsFileNotFoundException() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        FakeFileLookup fileLookup = new FakeFileLookup();
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        assertThatThrownBy(() -> fileService.restore(tenantId, actor, UUID.randomUUID()))
                .isInstanceOf(FileNotFoundException.class);
    }

    /**
     * Purge (issue #38) is the only way content is ever permanently removed (decision #7): once a
     * File has been explicitly trashed, purging it calls through to
     * {@code storageBackend.delete(file.getStorageReference())} and then deletes the metadata row
     * itself via {@link FileLookup#delete(File)}.
     */
    @Test
    void purgeDeletesStorageBackendContentAndTheMetadataRowForATrashedFile() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File existing = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", actor, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(existing.getId(), existing);
        FakeStorageBackend storageBackend = new FakeStorageBackend();
        FileService fileService = new FileService(fileLookup, folderService, storageBackend);

        fileService.trash(tenantId, actor, existing.getId());

        fileService.purge(tenantId, existing.getId());

        assertThat(storageBackend.deleteCalls).containsExactly(existing.getStorageReference());
        assertThat(fileLookup.deleted).containsExactly(existing);
    }

    /**
     * Purging anything not (explicitly) trashed is rejected (issue #38) - the guard checks the
     * File's own {@code trashedAt} only, mirroring how {@link #restore} only ever looks at a row's
     * own state (no ancestor walk). Rejected before ever reaching {@code StorageBackend} or the
     * metadata row.
     */
    @Test
    void purgeOfAFileThatIsNotTrashedThrowsIllegalArgumentException() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File existing = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", actor, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(existing.getId(), existing);
        FakeStorageBackend storageBackend = new FakeStorageBackend();
        FileService fileService = new FileService(fileLookup, folderService, storageBackend);

        assertThatThrownBy(() -> fileService.purge(tenantId, existing.getId()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(storageBackend.deleteCalls).isEmpty();
        assertThat(fileLookup.deleted).isEmpty();
    }

    @Test
    void purgeOfANonExistentFileThrowsFileNotFoundException() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        FakeFileLookup fileLookup = new FakeFileLookup();
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        assertThatThrownBy(() -> fileService.purge(tenantId, UUID.randomUUID()))
                .isInstanceOf(FileNotFoundException.class);
    }

    /**
     * Ordinary listings silently exclude trashed items, no filter flag needed (issue #37, ADR
     * 0004) - the cheap, per-row half of the exclusion rule, mirroring
     * {@code FolderServiceTest#listChildrenExcludesAChildWithItsOwnTrashedAtSet}: a File's own
     * {@code trashedAt} being set is enough to exclude it, with no ancestor walk needed.
     */
    @Test
    void listFilesExcludesAFileWithItsOwnTrashedAtSet() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID parentFolderId = UUID.randomUUID();
        File trashedFile = new File(UUID.randomUUID(), tenantId, parentFolderId, "Old Draft.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", actor, Instant.now());
        File activeFile = new File(UUID.randomUUID(), tenantId, parentFolderId, "Final.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", actor, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(trashedFile.getId(), trashedFile);
        fileLookup.byId.put(activeFile.getId(), activeFile);
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        fileService.trash(tenantId, actor, trashedFile.getId());

        List<File> files = fileService.listFiles(tenantId, parentFolderId);

        assertThat(files).extracting(File::getId).containsExactly(activeFile.getId());
    }

    /**
     * Trashing a Folder makes every File beneath it disappear from ordinary listings, with no
     * bulk write across those Files (issue #37, decision #16) - mirroring
     * {@code FolderServiceTest#listChildrenReturnsEmptyWhenTheParentItselfIsTrashed}: listing a
     * trashed Folder's own Files returns empty because the Folder itself is effectively trashed,
     * even though the File row's own {@code trashedAt} was never touched.
     */
    @Test
    void listFilesReturnsEmptyWhenTheParentFolderItselfIsTrashed() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder parent = folderService.create(tenantId, actor, "Quarterly Reports", null);
        entityManager.getTransaction().commit();

        File file = new File(UUID.randomUUID(), tenantId, parent.getId(), "Invoice.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", actor, Instant.now());
        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(file.getId(), file);
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, actor, parent.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<File> files = fileService.listFiles(tenantId, parent.getId());

        assertThat(files).isEmpty();

        // No bulk write across Files: the File's own row must be untouched by trashing its parent.
        assertThat(file.getTrashedAt()).isNull();
    }

    /**
     * The "effectively trashed" walk goes beyond the immediate parent (issue #37, decision #16) -
     * mirroring {@code FolderServiceTest#listChildrenReturnsEmptyWhenAnAncestorBeyondTheImmediateParentIsTrashed}:
     * in a hierarchy A -&gt; B, with a File filed directly under B, trashing A must still empty out
     * B's own File listing, proving the walk doesn't stop after one hop up from the File's direct
     * parent.
     */
    @Test
    void listFilesReturnsEmptyWhenAnAncestorBeyondTheImmediateParentIsTrashed() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder a = folderService.create(tenantId, actor, "A", null);
        Folder b = folderService.create(tenantId, actor, "B", a.getId());
        entityManager.getTransaction().commit();

        File file = new File(UUID.randomUUID(), tenantId, b.getId(), "Invoice.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", actor, Instant.now());
        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(file.getId(), file);
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, actor, a.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<File> filesUnderB = fileService.listFiles(tenantId, b.getId());

        assertThat(filesUnderB).isEmpty();
    }

    /**
     * {@code listTrashed} is the trash bin's own query (issue #37), mirroring
     * {@code FolderServiceTest#listTrashedScopedToParentReturnsOnlyDirectChildrenWithTheirOwnTrashedAtSet}:
     * unlike {@code listFiles}'s exclusion rule, it returns only Files with their own
     * {@code trashedAt} set, scoped to direct children of a given parent - an untrashed sibling,
     * and a File trashed under a completely different parent Folder, are both noise this list
     * must exclude.
     */
    @Test
    void listTrashedScopedToParentReturnsOnlyDirectChildrenWithTheirOwnTrashedAtSet() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder parent = folderService.create(tenantId, actor, "Quarterly Reports", null);
        Folder otherParent = folderService.create(tenantId, actor, "Unrelated Parent", null);
        entityManager.getTransaction().commit();

        File trashedFile = new File(UUID.randomUUID(), tenantId, parent.getId(), "Old Draft.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", actor, Instant.now());
        File activeFile = new File(UUID.randomUUID(), tenantId, parent.getId(), "Final.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", actor, Instant.now());
        File trashedUnderOtherParent = new File(UUID.randomUUID(), tenantId, otherParent.getId(),
                "Old Draft Elsewhere.pdf", 11, "application/pdf", Visibility.PRIVATE, "some/reference",
                actor, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(trashedFile.getId(), trashedFile);
        fileLookup.byId.put(activeFile.getId(), activeFile);
        fileLookup.byId.put(trashedUnderOtherParent.getId(), trashedUnderOtherParent);
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        fileService.trash(tenantId, actor, trashedFile.getId());
        fileService.trash(tenantId, actor, trashedUnderOtherParent.getId());

        List<File> trashed = fileService.listTrashed(tenantId, parent.getId());

        assertThat(trashed).extracting(File::getId).containsExactly(trashedFile.getId());
    }

    /**
     * With no {@code parentFolderId}, {@code listTrashed} is a flat, tenant-wide scan (issue #37),
     * mirroring
     * {@code FolderServiceTest#listTrashedWithNullParentReturnsEveryTrashedFolderTenantWideRegardlessOfNestingDepth}
     * - it finds every explicitly-trashed File regardless of which Folder it's parented under,
     * with no parent filter applied at all.
     */
    @Test
    void listTrashedWithNullParentReturnsEveryTrashedFileTenantWideRegardlessOfParent() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder parentOne = folderService.create(tenantId, actor, "Quarterly Reports", null);
        Folder parentTwo = folderService.create(tenantId, actor, "Invoices", null);
        entityManager.getTransaction().commit();

        File trashedInParentOne = new File(UUID.randomUUID(), tenantId, parentOne.getId(), "Old Draft.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", actor, Instant.now());
        File trashedInParentTwo = new File(UUID.randomUUID(), tenantId, parentTwo.getId(), "Old Invoice.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", actor, Instant.now());
        File activeFile = new File(UUID.randomUUID(), tenantId, parentOne.getId(), "Final.pdf", 11,
                "application/pdf", Visibility.PRIVATE, "some/reference", actor, Instant.now());

        FakeFileLookup fileLookup = new FakeFileLookup();
        fileLookup.byId.put(trashedInParentOne.getId(), trashedInParentOne);
        fileLookup.byId.put(trashedInParentTwo.getId(), trashedInParentTwo);
        fileLookup.byId.put(activeFile.getId(), activeFile);
        FileService fileService = new FileService(fileLookup, folderService, new FakeStorageBackend());

        fileService.trash(tenantId, actor, trashedInParentOne.getId());
        fileService.trash(tenantId, actor, trashedInParentTwo.getId());

        List<File> trashed = fileService.listTrashed(tenantId, null);

        assertThat(trashed).extracting(File::getId)
                .containsExactlyInAnyOrder(trashedInParentOne.getId(), trashedInParentTwo.getId());
    }

    /** Captures what it was asked to save, and answers lookups from a preloaded map. */
    private static final class FakeFileLookup implements FileLookup {
        private final Map<UUID, File> byId = new HashMap<>();
        private File saved;
        // Not yet declared on FileLookup itself (issue #38) - added here only as the minimal fake
        // wiring purge()'s test needs; production FileLookup/FileDao are deliberately untouched.
        private final List<File> deleted = new java.util.ArrayList<>();

        @Override
        public File save(File file) {
            this.saved = file;
            return file;
        }

        public void delete(File file) {
            deleted.add(file);
            byId.remove(file.getId());
        }

        @Override
        public File findByIdForTenant(UUID id, TenantId tenantId) {
            return byId.get(id);
        }

        // Mirrors FileDao#findByParentFolderForTenant's real query exactly: scoped to
        // parentFolderId, excluding Files with their own trashedAt set (issue #37) - a fake
        // standing in for a DAO must honor the same contract the DAO does, not diverge from it.
        @Override
        public List<File> findByParentFolderForTenant(UUID parentFolderId, TenantId tenantId) {
            List<File> matching = new java.util.ArrayList<>();
            for (File file : byId.values()) {
                if (file.getParentFolderId().equals(parentFolderId) && file.getTrashedAt() == null) {
                    matching.add(file);
                }
            }
            return matching;
        }

        @Override
        public List<File> findByParentFolderForTenant(
                UUID parentFolderId, TenantId tenantId, UUID afterId, int limit) {
            throw new AssertionError("not expected to be called by upload()/fetch()/fetchContent()");
        }

        // Mirrors FileDao#findAllByParentFolderForTenant's real query exactly: scoped to
        // parentFolderId, with no trashedAt exclusion at all (issue #38).
        @Override
        public List<File> findAllByParentFolderForTenant(UUID parentFolderId, TenantId tenantId) {
            List<File> matching = new java.util.ArrayList<>();
            for (File file : byId.values()) {
                if (file.getParentFolderId().equals(parentFolderId)) {
                    matching.add(file);
                }
            }
            return matching;
        }

        // Mirrors FileDao#findTrashedForTenant's real query exactly: own trashedAt set,
        // optionally scoped to parentFolderId (issue #37).
        @Override
        public List<File> findTrashedForTenant(UUID parentFolderId, TenantId tenantId) {
            List<File> matching = new java.util.ArrayList<>();
            for (File file : byId.values()) {
                if (file.getTrashedAt() != null
                        && (parentFolderId == null || file.getParentFolderId().equals(parentFolderId))) {
                    matching.add(file);
                }
            }
            return matching;
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
            public void delete(File file) {
                throw new AssertionError("not expected to be called by upload()");
            }

            @Override
            public File findByIdForTenant(UUID id, TenantId tenantId) {
                throw new AssertionError("not expected to be called by upload()");
            }

            @Override
            public List<File> findByParentFolderForTenant(UUID parentFolderId, TenantId tenantId) {
                throw new AssertionError("not expected to be called by upload()");
            }

            @Override
            public List<File> findByParentFolderForTenant(
                    UUID parentFolderId, TenantId tenantId, UUID afterId, int limit) {
                throw new AssertionError("not expected to be called by upload()");
            }

            @Override
            public List<File> findAllByParentFolderForTenant(UUID parentFolderId, TenantId tenantId) {
                throw new AssertionError("not expected to be called by upload()");
            }

            @Override
            public List<File> findTrashedForTenant(UUID parentFolderId, TenantId tenantId) {
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
