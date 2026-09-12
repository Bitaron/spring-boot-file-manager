package io.github.bitaron.filemanager.autoconfigure;

import javax.sql.DataSource;

import io.github.bitaron.filemanager.core.folder.Folder;
import io.github.bitaron.filemanager.core.folder.FolderService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/**
 * Embedded Mode wiring for Folder persistence (docs/architecture.md's "Embedded Mode reuses the
 * host's own DataSource" rule). Activates as soon as the host application exposes a single
 * {@link DataSource} bean - Folder persistence isn't behind a storage-backend-style property
 * switch, so unlike the {@code file-manager-storage-*} autoconfigurations this one has no
 * {@code @ConditionalOnProperty} guard.
 *
 * <p>Ordered ({@code before = HibernateJpaAutoConfiguration.class}) so {@link Folder}'s package is
 * folded into the host's entity scan (via {@link EntityScanPackages#register}) before Hibernate
 * builds its {@code EntityManagerFactory} - the same mechanism Spring Boot's own autoconfiguration
 * modules use to contribute entity packages, so the host never needs its own {@code @EntityScan}.
 */
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)
@ConditionalOnSingleCandidate(DataSource.class)
@Import(FolderPersistenceAutoConfiguration.FolderEntityScanRegistrar.class)
public class FolderPersistenceAutoConfiguration {

    /**
     * Exposes a shared, transaction-aware {@link EntityManager} bean so callers (including
     * {@link #folderService}) can depend on {@link EntityManager} directly rather than reaching
     * for {@code @PersistenceContext} field injection. Spring Boot's own JPA autoconfiguration
     * never registers a plain {@link EntityManager} bean itself (it leaves that to
     * {@code @PersistenceContext} injection), so this mirrors what that annotation synthesizes
     * under the hood, using the same {@link SharedEntityManagerCreator} Spring itself uses.
     */
    @Bean
    @ConditionalOnMissingBean(EntityManager.class)
    public EntityManager entityManager(EntityManagerFactory entityManagerFactory) {
        return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    }

    @Bean
    public FolderService folderService(EntityManager entityManager) {
        return new FolderService(entityManager);
    }

    /**
     * Folds {@code io.github.bitaron.filemanager.core.folder} (Folder's package) into the host's
     * JPA entity scan via {@link EntityScanPackages#register} - the standard mechanism Spring
     * Boot itself uses so an autoconfiguration can contribute entity packages without the host
     * declaring its own {@code @EntityScan}.
     */
    static class FolderEntityScanRegistrar implements ImportBeanDefinitionRegistrar {

        @Override
        public void registerBeanDefinitions(
                AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
            EntityScanPackages.register(registry, "io.github.bitaron.filemanager.core.folder");
        }
    }
}
