package io.github.bitaron.filemanager.core.trash;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.file.FileService;
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

/**
 * Unit tests for {@link TrashService}: no fake of its own is needed (docs/testing.md) - it has no
 * persistence seam, composing only {@link FolderService}/{@link FileService}, so this test backs
 * both with a real, in-memory-H2 {@link EntityManager} (via their own public constructors) plus a
 * tiny in-memory {@link StorageBackend} fake for {@code FileService#upload}, mirroring
 * {@code core.accesstoken.AccessTokenServiceTest}'s same real-H2-backed-dependencies pattern.
 */
class TrashServiceTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private FolderService folderService;
    private FileService fileService;
    private TrashService trashService;

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
        fileService = new FileService(entityManager, folderService, new InMemoryStorageBackend());
        trashService = new TrashService(folderService, fileService);
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    /**
     * {@code TrashService} merges both aggregates' own {@code listTrashed} calls into one list
     * (issue #37, decision #17's fourth granular service), discriminating Folder vs. File and
     * sorting by {@code trashedAt} descending. A trashed Folder and a trashed File - parented
     * under entirely different contexts (the Folder is top-level, the File sits under a second,
     * unrelated Folder) - are trashed with a real time gap between them so the sort is actually
     * verifiable, not coincidental (the Folder is trashed first, so it must sort second/last).
     */
    @Test
    void listMergesTrashedFolderAndFileDiscriminatedAndSortedByTrashedAtDescending() throws InterruptedException {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder oldProject = folderService.create(tenantId, actor, "Old Project", null);
        Folder documents = folderService.create(tenantId, actor, "Documents", null);
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
        File notes = fileService.upload(tenantId, actor, documents.getId(), "notes.txt", null,
                new ByteArrayInputStream(bytes), bytes.length, "text/plain");
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, actor, oldProject.getId());
        entityManager.getTransaction().commit();

        // A real, measurable gap so the two trashedAt values are guaranteed distinct - the sort
        // assertion below would otherwise be coincidental rather than actually exercised.
        Thread.sleep(50);

        entityManager.getTransaction().begin();
        fileService.trash(tenantId, actor, notes.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<TrashItem> items = trashService.list(tenantId, null);

        assertThat(items).hasSize(2);

        // The File was trashed later, so it sorts first (trashedAt descending).
        assertThat(items.get(0)).isInstanceOf(TrashItem.TrashedFile.class);
        TrashItem.TrashedFile fileItem = (TrashItem.TrashedFile) items.get(0);
        assertThat(fileItem.file().getId()).isEqualTo(notes.getId());
        assertThat(fileItem.file().getName()).isEqualTo("notes.txt");

        assertThat(items.get(1)).isInstanceOf(TrashItem.TrashedFolder.class);
        TrashItem.TrashedFolder folderItem = (TrashItem.TrashedFolder) items.get(1);
        assertThat(folderItem.folder().getId()).isEqualTo(oldProject.getId());
        assertThat(folderItem.folder().getName()).isEqualTo("Old Project");

        assertThat(fileItem.file().getTrashedAt()).isAfter(folderItem.folder().getTrashedAt());
    }

    /** A tiny in-memory {@link StorageBackend}: content actually round-trips, unlike a call-recording fake. */
    private static final class InMemoryStorageBackend implements StorageBackend {
        private final Map<String, byte[]> contents = new HashMap<>();

        @Override
        public String store(String key, InputStream content, long contentLength, String contentType) {
            try {
                contents.put(key, content.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return key;
        }

        @Override
        public InputStream retrieve(String reference) {
            byte[] bytes = contents.get(reference);
            if (bytes == null) {
                throw new AssertionError("no content stored for reference " + reference);
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void delete(String reference) {
            contents.remove(reference);
        }

        @Override
        public boolean exists(String reference) {
            return contents.containsKey(reference);
        }
    }
}
