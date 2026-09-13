package io.github.bitaron.filemanager.autoconfigure;

import javax.sql.DataSource;

import io.github.bitaron.filemanager.core.accesstoken.AccessToken;
import io.github.bitaron.filemanager.core.accesstoken.AccessTokenService;
import io.github.bitaron.filemanager.core.file.FileService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/**
 * Embedded Mode wiring for AccessToken persistence (issue #36's Slice C, mirroring {@link
 * FilePersistenceAutoConfiguration}'s shape exactly). Folds {@link AccessToken}'s package into the
 * host's shared entity scan and exposes a working {@link AccessTokenService} bean wired against
 * the host's own {@link DataSource}, a {@link FileService} bean (for the mint-time Visibility
 * check + redeem's content-fetch), and the two {@code @Value}-injected per-Purpose TTL ceilings.
 *
 * <p>{@link #accessTokenService} is marked {@link Lazy} for the same reason
 * {@link FilePersistenceAutoConfiguration#fileService} is: an eager {@code @Bean} method here
 * would be instantiated at context refresh for every host with a {@link DataSource}, even ones
 * that never opted into File support at all (and thus have no {@link FileService} bean) - {@code
 * @Lazy} defers construction to first real use instead.
 *
 * <p>Ordered {@code before = HibernateJpaAutoConfiguration.class} and
 * {@code after = DataSourceAutoConfiguration.class} for the same reasons as
 * {@link FilePersistenceAutoConfiguration}.
 */
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class, after = DataSourceAutoConfiguration.class)
@ConditionalOnSingleCandidate(DataSource.class)
@Import(AccessTokenPersistenceAutoConfiguration.AccessTokenEntityScanRegistrar.class)
public class AccessTokenPersistenceAutoConfiguration {

    /**
     * Exposes a shared, transaction-aware {@link EntityManager} bean - see
     * {@link FilePersistenceAutoConfiguration#entityManager} for why. Backs off cleanly via
     * {@link ConditionalOnMissingBean} if another persistence autoconfiguration already registered
     * one.
     */
    @Bean
    @ConditionalOnMissingBean(EntityManager.class)
    public EntityManager entityManager(EntityManagerFactory entityManagerFactory) {
        return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    }

    @Bean
    @Lazy
    public AccessTokenService accessTokenService(
            EntityManager entityManager,
            FileService fileService,
            @Value("${file-manager.access-token.max-ttl-seconds.view:86400}") long viewTtlCeilingSeconds,
            @Value("${file-manager.access-token.max-ttl-seconds.download:86400}") long downloadTtlCeilingSeconds) {
        return new AccessTokenService(entityManager, fileService, viewTtlCeilingSeconds, downloadTtlCeilingSeconds);
    }

    /**
     * Folds {@code io.github.bitaron.filemanager.core.accesstoken} ({@link AccessToken}'s package)
     * into the host's JPA entity scan - see
     * {@link FolderPersistenceAutoConfiguration.FolderEntityScanRegistrar} for why this mechanism
     * is used instead of a host-declared {@code @EntityScan}.
     */
    static class AccessTokenEntityScanRegistrar implements ImportBeanDefinitionRegistrar {

        @Override
        public void registerBeanDefinitions(
                AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
            EntityScanPackages.register(registry, "io.github.bitaron.filemanager.core.accesstoken");
        }
    }
}
