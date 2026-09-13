package io.github.bitaron.filemanager.core.folder;

import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.jpa.TestEntityManagerFactory;
import io.github.bitaron.filemanager.core.tenant.TenantId;
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
 * Exercises {@link FolderService} behavior that genuinely needs persistence (create + reload,
 * parent-existence lookup) against a real {@link EntityManager}/Hibernate, backed by an
 * in-memory H2 database - no Spring context involved (docs/testing.md: core's domain rules are
 * tested with no Spring context). Pure guard-clause validation lives in
 * {@link FolderServiceValidationTest} instead, against a fake {@link FolderLookup}.
 */
class FolderServiceTest {

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
    void createsTopLevelFolderWithNoParent() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder created = folderService.create(tenantId, actor, "Quarterly Reports", null);
        entityManager.getTransaction().commit();

        // Clear the persistence context so the lookup below hits the database rather than
        // returning the same managed instance still cached in memory.
        entityManager.clear();
        Folder reloaded = entityManager.find(Folder.class, created.getId());

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getParentFolderId()).isNull();
        assertThat(reloaded.tenantId()).isEqualTo(tenantId);
        assertThat(reloaded.getName()).isEqualTo("Quarterly Reports");
    }

    @Test
    void createsNestedFolderWithParentReference() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder parent = folderService.create(tenantId, actor, "Quarterly Reports", null);
        Folder child = folderService.create(tenantId, actor, "2026 Q1", parent.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder reloadedChild = entityManager.find(Folder.class, child.getId());

        assertThat(reloadedChild).isNotNull();
        assertThat(reloadedChild.getParentFolderId()).isEqualTo(parent.getId());
    }

    @Test
    void allowsSiblingFoldersWithSameName() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder first = folderService.create(tenantId, actor, "Invoices", null);
        Folder second = folderService.create(tenantId, actor, "Invoices", null);
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
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID nonexistentParentId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        try {
            assertThatThrownBy(() -> folderService.create(tenantId, actor, "Orphan", nonexistentParentId))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            entityManager.getTransaction().rollback();
        }
    }
}
