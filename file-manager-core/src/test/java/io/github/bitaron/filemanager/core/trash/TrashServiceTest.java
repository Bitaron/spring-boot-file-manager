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
    private InMemoryStorageBackend storageBackend;

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
        storageBackend = new InMemoryStorageBackend();
        fileService = new FileService(entityManager, folderService, storageBackend);
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

    /**
     * The Folder-cascade purge orchestrator (issue #38): trashing the root Folder, then purging
     * it, must delete every descendant Folder's and File's metadata row - and every descendant
     * File's {@code StorageBackend} content - down an arbitrarily deep subtree (adjacency list,
     * no materialized path, per ADR 0003), regardless of whether any given descendant was ever
     * individually trashed itself. The tree here is 3 levels deep (root -&gt; child -&gt;
     * grandchild) with a File directly under each level; the grandchild Folder is trashed
     * independently, partway down, before the root is trashed and purged, while the child Folder
     * and every File are never explicitly trashed at all - all of it must vanish just the same,
     * since the cascade only ever checks the *root's* own trashed state (mirrors
     * {@code FileService#purge}'s "own row only" guard - issue #37 decision #16's
     * own-state-only rule, extended to the purge cascade).
     */
    @Test
    void purgeCascadesThroughAnArbitrarilyDeepFolderTreeRegardlessOfEachDescendantsOwnTrashedState() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder root = folderService.create(tenantId, actor, "Root", null);
        Folder child = folderService.create(tenantId, actor, "Child", root.getId());
        Folder grandchild = folderService.create(tenantId, actor, "Grandchild", child.getId());
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        File rootFile = uploadFile(tenantId, actor, root.getId(), "root.txt");
        File childFile = uploadFile(tenantId, actor, child.getId(), "child.txt");
        File grandchildFile = uploadFile(tenantId, actor, grandchild.getId(), "grandchild.txt");
        entityManager.getTransaction().commit();

        // The grandchild Folder is trashed independently, partway down the subtree, well before
        // the root purge below - it (and its File) must still be purged, since the cascade never
        // consults a descendant's own trashed state.
        entityManager.getTransaction().begin();
        folderService.trash(tenantId, actor, grandchild.getId());
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, actor, root.getId());
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        trashService.purge(tenantId, root.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();

        assertThat(entityManager.find(Folder.class, root.getId())).isNull();
        assertThat(entityManager.find(Folder.class, child.getId())).isNull();
        assertThat(entityManager.find(Folder.class, grandchild.getId())).isNull();

        assertThat(entityManager.find(File.class, rootFile.getId())).isNull();
        assertThat(entityManager.find(File.class, childFile.getId())).isNull();
        assertThat(entityManager.find(File.class, grandchildFile.getId())).isNull();

        // File.getStorageReference() is deliberately package-private (never leaks past
        // core.file) - this asserts via the File's own id instead, which FileService#upload
        // documents as the StorageBackend key it stores content under.
        assertThat(storageBackend.exists(rootFile.getId().toString())).isFalse();
        assertThat(storageBackend.exists(childFile.getId().toString())).isFalse();
        assertThat(storageBackend.exists(grandchildFile.getId().toString())).isFalse();
    }

    /**
     * Purging anything not (explicitly) trashed is rejected (issue #38) - the guard checks the
     * root Folder's own {@code trashedAt} only, mirroring how {@code FolderService#restore} only
     * ever looks at a row's own state (no ancestor walk, and per AGENT-BRIEF.md, *not*
     * {@code FolderService#isTrashed}'s ancestor-computed "effectively trashed" state either).
     * Rejected before any row or {@code StorageBackend} content is touched.
     */
    @Test
    void purgeOfAFolderThatIsNotExplicitlyTrashedThrowsIllegalArgumentExceptionAndDeletesNothing() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder folder = folderService.create(tenantId, actor, "Documents", null);
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        File file = uploadFile(tenantId, actor, folder.getId(), "notes.txt");
        entityManager.getTransaction().commit();

        assertThatThrownBy(() -> trashService.purge(tenantId, folder.getId()))
                .isInstanceOf(IllegalArgumentException.class);

        entityManager.clear();

        assertThat(entityManager.find(Folder.class, folder.getId())).isNotNull();
        assertThat(entityManager.find(File.class, file.getId())).isNotNull();
        assertThat(storageBackend.exists(file.getId().toString())).isTrue();
    }

    /** Mirrors {@code FolderService#fetch}'s "no such Folder for this Tenant" convention. */
    @Test
    void purgeOfANonexistentFolderThrowsFolderNotFoundException() {
        TenantId tenantId = new TenantId(UUID.randomUUID());

        assertThatThrownBy(() -> trashService.purge(tenantId, UUID.randomUUID()))
                .isInstanceOf(FolderNotFoundException.class);
    }

    private File uploadFile(TenantId tenantId, Actor actor, UUID parentFolderId, String name) {
        byte[] bytes = ("content of " + name).getBytes(StandardCharsets.UTF_8);
        return fileService.upload(tenantId, actor, parentFolderId, name, null,
                new ByteArrayInputStream(bytes), bytes.length, "text/plain");
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
