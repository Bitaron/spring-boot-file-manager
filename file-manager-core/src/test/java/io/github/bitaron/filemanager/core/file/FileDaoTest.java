package io.github.bitaron.filemanager.core.file;

import java.time.Instant;
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
}
