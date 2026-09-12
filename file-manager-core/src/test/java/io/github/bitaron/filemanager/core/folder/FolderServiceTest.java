package io.github.bitaron.filemanager.core.folder;

import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link FolderService} directly against a real {@link EntityManager}/Hibernate,
 * backed by an in-memory H2 database - no Spring context involved (docs/testing.md: core's
 * domain rules are tested with no Spring context).
 */
class FolderServiceTest {

    private EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;
    private FolderService folderService;

    @BeforeEach
    void setUp() {
        entityManagerFactory = Persistence.createEntityManagerFactory("file-manager-core-test");
        entityManager = entityManagerFactory.createEntityManager();
        folderService = new FolderService(entityManager);
    }

    @AfterEach
    void tearDown() {
        entityManager.close();
        entityManagerFactory.close();
    }

    @Test
    void createsTopLevelFolderWithNoParent() {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        Folder created = folderService.create(tenantId, actorId, "Quarterly Reports", null);
        entityManager.getTransaction().commit();

        // Clear the persistence context so the lookup below hits the database rather than
        // returning the same managed instance still cached in memory.
        entityManager.clear();
        Folder reloaded = entityManager.find(Folder.class, created.getId());

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getParentFolderId()).isNull();
        assertThat(reloaded.getTenantId()).isEqualTo(tenantId);
        assertThat(reloaded.getName()).isEqualTo("Quarterly Reports");
    }

    @Test
    void createsNestedFolderWithParentReference() {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        Folder parent = folderService.create(tenantId, actorId, "Quarterly Reports", null);
        Folder child = folderService.create(tenantId, actorId, "2026 Q1", parent.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder reloadedChild = entityManager.find(Folder.class, child.getId());

        assertThat(reloadedChild).isNotNull();
        assertThat(reloadedChild.getParentFolderId()).isEqualTo(parent.getId());
    }

    @Test
    void allowsSiblingFoldersWithSameName() {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        Folder first = folderService.create(tenantId, actorId, "Invoices", null);
        Folder second = folderService.create(tenantId, actorId, "Invoices", null);
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder reloadedFirst = entityManager.find(Folder.class, first.getId());
        Folder reloadedSecond = entityManager.find(Folder.class, second.getId());

        assertThat(reloadedFirst).isNotNull();
        assertThat(reloadedSecond).isNotNull();
        assertThat(reloadedFirst.getId()).isNotEqualTo(reloadedSecond.getId());
        assertThat(reloadedFirst.getName()).isEqualTo("Invoices");
        assertThat(reloadedSecond.getName()).isEqualTo("Invoices");
    }

    @Test
    void rejectsCreateWithNonexistentParent() {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID nonexistentParentId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        try {
            assertThatThrownBy(() -> folderService.create(tenantId, actorId, "Orphan", nonexistentParentId))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            entityManager.getTransaction().rollback();
        }
    }

    @Test
    void rejectsCreateWithBlankName() {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        try {
            assertThatThrownBy(() -> folderService.create(tenantId, actorId, "  ", null))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            entityManager.getTransaction().rollback();
        }
    }

    @Test
    void rejectsCreateWithNullName() {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        try {
            assertThatThrownBy(() -> folderService.create(tenantId, actorId, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            entityManager.getTransaction().rollback();
        }
    }

    @Test
    void rejectsCreateWithNullTenantId() {
        UUID actorId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        try {
            assertThatThrownBy(() -> folderService.create(null, actorId, "Orphan", null))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            entityManager.getTransaction().rollback();
        }
    }

    @Test
    void rejectsCreateWithNullActorId() {
        UUID tenantId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        try {
            assertThatThrownBy(() -> folderService.create(tenantId, null, "Orphan", null))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            entityManager.getTransaction().rollback();
        }
    }
}
