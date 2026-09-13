package io.github.bitaron.filemanager.core.file;

import java.time.Instant;
import java.util.List;
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

/**
 * Proves {@link FileDao}'s persistence mechanics against a real, in-memory-H2
 * {@link EntityManager} - no Spring context (docs/testing.md). No equivalent {@code FolderDaoTest}
 * exists as prior art (Folder's DAO behavior is only exercised indirectly, through
 * {@code FolderServiceTest}'s real-{@code EntityManager} setup); this class exists because
 * {@code core.file}'s own {@code FileServiceTest} fakes {@link FileLookup} out entirely, leaving
 * {@link FileDao} itself otherwise untested.
 */
class FileDaoTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private FileDao fileDao;

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
        fileDao = new FileDao(entityManager);
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    void savePersistsAndFindByIdForTenantReturnsIt() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File file = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11, "application/pdf",
                Visibility.PRIVATE, "some/reference", actor, Instant.now());

        entityManager.getTransaction().begin();
        File saved = fileDao.save(file);
        entityManager.getTransaction().commit();

        assertThat(saved).isSameAs(file);

        File found = fileDao.findByIdForTenant(file.getId(), tenantId);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(file.getId());
        assertThat(found.getName()).isEqualTo("invoice.pdf");
    }

    @Test
    void findByIdForTenantReturnsNullForCrossTenantLookup() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        TenantId otherTenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File file = new File(UUID.randomUUID(), tenantId, UUID.randomUUID(), "invoice.pdf", 11, "application/pdf",
                Visibility.PRIVATE, "some/reference", actor, Instant.now());

        entityManager.getTransaction().begin();
        fileDao.save(file);
        entityManager.getTransaction().commit();

        File found = fileDao.findByIdForTenant(file.getId(), otherTenantId);
        assertThat(found).isNull();
    }

    @Test
    void findByIdForTenantReturnsNullForUnknownId() {
        TenantId tenantId = new TenantId(UUID.randomUUID());

        File found = fileDao.findByIdForTenant(UUID.randomUUID(), tenantId);
        assertThat(found).isNull();
    }

    @Test
    void findByParentFolderForTenantListsOnlyFilesForTheGivenParentFolderAndTenant() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID parentFolderId = UUID.randomUUID();
        File file1 = newFile(tenantId, parentFolderId, "a.pdf", actor);
        File file2 = newFile(tenantId, parentFolderId, "b.pdf", actor);
        // A File under a different parent Folder is noise this list must exclude.
        File otherParent = newFile(tenantId, UUID.randomUUID(), "c.pdf", actor);

        entityManager.getTransaction().begin();
        fileDao.save(file1);
        fileDao.save(file2);
        fileDao.save(otherParent);
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<File> files = fileDao.findByParentFolderForTenant(parentFolderId, tenantId);

        assertThat(files).extracting(File::getId)
                .containsExactlyInAnyOrder(file1.getId(), file2.getId());
    }

    /**
     * Folder ids are globally unique UUIDs, but nothing in {@link File}'s schema enforces that a
     * {@code parent_folder_id} value actually belongs to the querying Tenant - so this list must
     * exclude another Tenant's File even when its {@code parentFolderId} happens to match.
     */
    @Test
    void findByParentFolderForTenantExcludesAnotherTenantsFileWithTheSameParentFolderId() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        TenantId otherTenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID parentFolderId = UUID.randomUUID();
        File mine = newFile(tenantId, parentFolderId, "mine.pdf", actor);
        File someoneElses = newFile(otherTenantId, parentFolderId, "someone-elses.pdf", actor);

        entityManager.getTransaction().begin();
        fileDao.save(mine);
        fileDao.save(someoneElses);
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<File> files = fileDao.findByParentFolderForTenant(parentFolderId, tenantId);

        assertThat(files).extracting(File::getId).containsExactly(mine.getId());
    }

    /**
     * UUIDv7 only orders by millisecond timestamp (see {@code UuidV7}) - Files created within the
     * same millisecond have no guaranteed relative order, so this test derives "the" id order from
     * an unbounded page (the same {@code ORDER BY id ASC} query under test) instead of assuming
     * creation order, to avoid flaking on a fast test run - mirroring the equivalent Folder
     * pagination tests' rationale.
     */
    @Test
    void findByParentFolderForTenantPagedReturnsAtMostLimitFilesInIdOrder() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID parentFolderId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        fileDao.save(newFile(tenantId, parentFolderId, "a.pdf", actor));
        fileDao.save(newFile(tenantId, parentFolderId, "b.pdf", actor));
        fileDao.save(newFile(tenantId, parentFolderId, "c.pdf", actor));
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<File> idOrder = fileDao.findByParentFolderForTenant(parentFolderId, tenantId, null, 50);
        List<File> firstPage = fileDao.findByParentFolderForTenant(parentFolderId, tenantId, null, 2);

        assertThat(firstPage).extracting(File::getId)
                .containsExactlyElementsOf(idOrder.stream().limit(2).map(File::getId).toList());
    }

    @Test
    void findByParentFolderForTenantPagedResumesAfterTheGivenCursor() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID parentFolderId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        fileDao.save(newFile(tenantId, parentFolderId, "a.pdf", actor));
        fileDao.save(newFile(tenantId, parentFolderId, "b.pdf", actor));
        fileDao.save(newFile(tenantId, parentFolderId, "c.pdf", actor));
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<File> idOrder = fileDao.findByParentFolderForTenant(parentFolderId, tenantId, null, 50);
        UUID cursor = idOrder.get(0).getId();

        List<File> nextPage = fileDao.findByParentFolderForTenant(parentFolderId, tenantId, cursor, 50);

        assertThat(nextPage).extracting(File::getId)
                .containsExactlyElementsOf(idOrder.stream().skip(1).map(File::getId).toList());
    }

    private static File newFile(TenantId tenantId, UUID parentFolderId, String name, Actor actor) {
        return new File(UUID.randomUUID(), tenantId, parentFolderId, name, 11, "application/pdf",
                Visibility.PRIVATE, "some/reference", actor, Instant.now());
    }
}
