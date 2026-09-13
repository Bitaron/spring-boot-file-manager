package io.github.bitaron.filemanager.starter;

import java.util.UUID;

import javax.sql.DataSource;

import io.github.bitaron.filemanager.core.apikey.ApiKey;
import io.github.bitaron.filemanager.core.apikey.ApiKeyHasher;
import io.github.bitaron.filemanager.core.apikey.ApiKeyResolution;
import io.github.bitaron.filemanager.core.apikey.ApiKeyResolver;
import io.github.bitaron.filemanager.core.apikey.ApiKeySecret;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.EntityManager;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Embedded Mode integration test for {@link ApiKeyResolver} (decision #17's "optional
 * ApiKeyResolver bean for hosts that want it"), mirroring
 * {@link FolderPersistenceIntegrationTest}'s shape: boots the starter with only a
 * {@link DataSource} bean supplied and proves the autoconfigure folds {@link ApiKey} into the
 * host's own JPA entity scan and exposes a working {@link ApiKeyResolver} bean wired against that
 * same {@link DataSource}.
 */
@SpringBootTest(
        classes = ApiKeyPersistenceIntegrationTest.TestApplication.class,
        properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional
class ApiKeyPersistenceIntegrationTest {

    @Autowired
    private ApiKeyResolver apiKeyResolver;

    @Autowired
    private EntityManager entityManager;

    @Test
    void resolvesAnApiKeyPersistedAgainstTheHostDataSource() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        String secret = ApiKeySecret.generate().value();
        ApiKey apiKey = ApiKey.issue(tenantId, "CI key", ApiKeyHasher.hash(secret));

        entityManager.persist(apiKey);
        entityManager.flush();
        entityManager.clear();

        ApiKeyResolution resolution = apiKeyResolver.resolve(secret);

        assertThat(resolution).isInstanceOf(ApiKeyResolution.Authenticated.class);
        assertThat(((ApiKeyResolution.Authenticated) resolution).tenantId()).isEqualTo(tenantId);
    }

    @Test
    void treatsARevokedApiKeyAsNotAuthenticated() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        String secret = ApiKeySecret.generate().value();
        ApiKey apiKey = ApiKey.issue(tenantId, "CI key", ApiKeyHasher.hash(secret));
        apiKey.revoke();

        entityManager.persist(apiKey);
        entityManager.flush();
        entityManager.clear();

        ApiKeyResolution resolution = apiKeyResolver.resolve(secret);

        assertThat(resolution).isInstanceOf(ApiKeyResolution.NotAuthenticated.class);
    }

    /**
     * Minimal host application: only a {@link DataSource} bean, same as
     * {@link FolderPersistenceIntegrationTest.TestApplication} - everything else (entity scan
     * registration, {@code EntityManagerFactory}, {@code ApiKeyResolver} bean) is expected to
     * come from the file-manager autoconfigure.
     */
    @SpringBootApplication
    static class TestApplication {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:apikey-persistence-it;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }
    }
}
