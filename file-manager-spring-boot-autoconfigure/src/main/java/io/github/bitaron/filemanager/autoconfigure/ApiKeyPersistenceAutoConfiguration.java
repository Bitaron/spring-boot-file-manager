package io.github.bitaron.filemanager.autoconfigure;

import javax.sql.DataSource;

import io.github.bitaron.filemanager.core.apikey.ApiKey;
import io.github.bitaron.filemanager.core.apikey.ApiKeyLookup;
import io.github.bitaron.filemanager.core.apikey.ApiKeyRepository;
import io.github.bitaron.filemanager.core.apikey.ApiKeyResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/**
 * Embedded Mode wiring for ApiKey resolution (decision #17: "an optional {@code ApiKeyResolver}
 * bean for hosts that want to authenticate via ApiKey inside Embedded Mode anyway"). Mirrors
 * {@link FolderPersistenceAutoConfiguration}'s shape exactly, and deliberately doesn't depend on
 * it - Folder and ApiKey are independent features that each fold their own entity package into
 * the shared scan and each back off via {@code @ConditionalOnMissingBean} if the other's
 * {@link EntityManager} bean already won.
 *
 * <p>This is also what the Standalone Service's {@code /api/v1} filter resolves ApiKeys through
 * (docs/api-design.md's "resolve the ApiKey to its Tenant before any Folder/File lookup" rule) -
 * one resolution mechanism, reused by both Embedded Mode and the REST layer, matching
 * {@code file-manager-core}'s "one API, two ways to reach it" rule.
 *
 * <p>Ordered {@code after = DataSourceAutoConfiguration.class} for the same reason
 * {@link FolderPersistenceAutoConfiguration} is: without it, a {@code DataSource} supplied by
 * Spring Boot's own {@code DataSourceAutoConfiguration} (e.g. its embedded-database
 * auto-configuration) might not exist yet when {@link ConditionalOnSingleCandidate} here is
 * evaluated, silently skipping all of ApiKey persistence's wiring.
 */
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class, after = DataSourceAutoConfiguration.class)
@ConditionalOnSingleCandidate(DataSource.class)
@Import(ApiKeyPersistenceAutoConfiguration.ApiKeyEntityScanRegistrar.class)
public class ApiKeyPersistenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EntityManager.class)
    public EntityManager entityManager(EntityManagerFactory entityManagerFactory) {
        return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    }

    @Bean
    @ConditionalOnMissingBean(ApiKeyLookup.class)
    public ApiKeyLookup apiKeyLookup(EntityManager entityManager) {
        return new ApiKeyRepository(entityManager);
    }

    @Bean
    @ConditionalOnMissingBean(ApiKeyResolver.class)
    public ApiKeyResolver apiKeyResolver(ApiKeyLookup apiKeyLookup) {
        return new ApiKeyResolver(apiKeyLookup);
    }

    /**
     * Folds {@code io.github.bitaron.filemanager.core.apikey} ({@link ApiKey}'s package) into the
     * host's JPA entity scan - see {@link FolderPersistenceAutoConfiguration.FolderEntityScanRegistrar}
     * for why this mechanism is used instead of a host-declared {@code @EntityScan}.
     * {@link EntityScanPackages#register} accumulates package names, so this and Folder's own
     * registrar both contribute to the same underlying entity scan.
     */
    static class ApiKeyEntityScanRegistrar implements ImportBeanDefinitionRegistrar {

        @Override
        public void registerBeanDefinitions(
                AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
            EntityScanPackages.register(registry, "io.github.bitaron.filemanager.core.apikey");
        }
    }
}
