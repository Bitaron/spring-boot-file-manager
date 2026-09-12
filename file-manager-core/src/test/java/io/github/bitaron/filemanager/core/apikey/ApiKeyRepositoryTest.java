package io.github.bitaron.filemanager.core.apikey;

import java.util.Optional;

import io.github.bitaron.filemanager.core.jpa.TestEntityManagerFactory;
import io.github.bitaron.filemanager.core.tenant.Tenant;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ApiKeyRepository} against a real EntityManager (Hibernate + in-memory H2), so
 * the indexed {@code key_hash} lookup is proven against actual JPA mapping, not a fake.
 */
class ApiKeyRepositoryTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private ApiKeyRepository apiKeyRepository;

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
        apiKeyRepository = new ApiKeyRepository(entityManager);
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    void findsAnApiKeyByItsExactKeyHash() {
        Tenant tenant = Tenant.create("Acme Inc.");
        String keyHash = ApiKeyHasher.hash(ApiKeySecret.generate().value());
        ApiKey apiKey = ApiKey.issue(new TenantId(tenant.getId()), "CI key", keyHash);
        persist(tenant, apiKey);

        Optional<ApiKey> found = apiKeyRepository.findByKeyHash(keyHash);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(apiKey.getId());
        assertThat(found.get().tenantId().id()).isEqualTo(tenant.getId());
    }

    @Test
    void returnsEmptyWhenNoApiKeyMatchesTheHash() {
        Optional<ApiKey> found = apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("fm_" + "z".repeat(43)));

        assertThat(found).isEmpty();
    }

    @Test
    void findsARevokedApiKeyTooLeavingRevocationCheckingToTheCaller() {
        Tenant tenant = Tenant.create("Acme Inc.");
        String keyHash = ApiKeyHasher.hash(ApiKeySecret.generate().value());
        ApiKey apiKey = ApiKey.issue(new TenantId(tenant.getId()), null, keyHash);
        apiKey.revoke();
        persist(tenant, apiKey);

        Optional<ApiKey> found = apiKeyRepository.findByKeyHash(keyHash);

        assertThat(found).isPresent();
        assertThat(found.get().isRevoked()).isTrue();
    }

    private void persist(Tenant tenant, ApiKey apiKey) {
        EntityTransaction transaction = entityManager.getTransaction();
        transaction.begin();
        entityManager.persist(tenant);
        entityManager.persist(apiKey);
        transaction.commit();
        entityManager.clear();
    }
}
