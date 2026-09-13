package io.github.bitaron.filemanager.core.accesstoken;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
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
 * Proves {@link AccessTokenDao}'s persistence mechanics against a real, in-memory-H2
 * {@link EntityManager} - no Spring context (docs/testing.md), mirroring
 * {@code core.file.FileDaoTest}. {@code fileId}/{@code tenantId} are plain UUID columns with no
 * JPA-level foreign key (ADR 0003, decision #10), so no real Tenant/File row needs to exist for
 * these tests.
 */
class AccessTokenDaoTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private AccessTokenDao accessTokenDao;

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
        accessTokenDao = new AccessTokenDao(entityManager);
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    void savePersistsAndFindByTokenReturnsIt() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        Instant now = Instant.now();
        AccessToken accessToken = new AccessToken("test-token-value", tenantId, UUID.randomUUID(), Purpose.VIEW,
                now.plus(15, ChronoUnit.MINUTES), actor, now);

        entityManager.getTransaction().begin();
        AccessToken saved = accessTokenDao.save(accessToken);
        entityManager.getTransaction().commit();

        assertThat(saved).isSameAs(accessToken);

        Optional<AccessToken> found = accessTokenDao.findByToken("test-token-value");
        assertThat(found).isPresent();
        assertThat(found.get().getToken()).isEqualTo("test-token-value");
        assertThat(found.get().getPurpose()).isEqualTo(Purpose.VIEW);
        assertThat(found.get().tenantId()).isEqualTo(tenantId);
        assertThat(found.get().mintedBy()).isEqualTo(actor);
    }

    @Test
    void findByTokenReturnsEmptyForUnknownToken() {
        Optional<AccessToken> found = accessTokenDao.findByToken("does-not-exist");

        assertThat(found).isEmpty();
    }
}
