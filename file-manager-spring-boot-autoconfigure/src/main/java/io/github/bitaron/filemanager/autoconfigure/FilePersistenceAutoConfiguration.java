package io.github.bitaron.filemanager.autoconfigure;

import javax.sql.DataSource;

import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.file.FileService;
import io.github.bitaron.filemanager.core.folder.FolderService;
import io.github.bitaron.filemanager.storage.StorageBackend;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/**
 * Embedded Mode wiring for File persistence + storage (docs/architecture.md's "Embedded Mode
 * reuses the host's own DataSource" rule). Mirrors {@link FolderPersistenceAutoConfiguration}'s
 * shape exactly, and deliberately doesn't depend on it - Folder and File each fold their own
 * entity package into the shared scan and each back off via {@code @ConditionalOnMissingBean} if
 * the other's {@link EntityManager} bean already won. Its {@link #fileService} bean method still
 * needs a {@link FolderService} bean (for the folder-existence check on upload) and a
 * {@link StorageBackend} bean (for content storage) - both are expected to already exist, supplied
 * by {@link FolderPersistenceAutoConfiguration} and the active storage module's autoconfiguration
 * (e.g. {@code LocalStorageAutoConfiguration}) respectively. {@link #fileService} is marked
 * {@link Lazy} - empirically necessary (not assumed): a plain, eager {@code @Bean} method here
 * gets instantiated at context refresh for every host with a {@link DataSource}, even ones that
 * never opted into File support at all (e.g. a host wiring only
 * {@code FolderPersistenceAutoConfiguration}'s {@code FolderService}, with no
 * {@code file-manager.storage.backend} property set and thus no {@link StorageBackend} bean) -
 * this broke {@code FolderPersistenceIntegrationTest} and {@code ApiKeyPersistenceIntegrationTest}
 * when first tried without {@code @Lazy}. A {@code @ConditionalOnBean(StorageBackend.class)} guard
 * was tried next, but it hit Spring Boot's well-known cross-module {@code @ConditionalOnBean}
 * ordering pitfall: this class and each storage module's autoconfiguration live in separate
 * modules/{@code AutoConfiguration.imports} files with no ordering relationship declared between
 * them, so {@link StorageBackend}'s bean definition wasn't reliably visible yet when this
 * condition was evaluated - it broke the very {@code FilePersistenceIntegrationTest} it needed to
 * satisfy. {@code @Lazy} sidesteps needing any such ordering entirely: the bean definition still
 * registers unconditionally (so {@code file-manager-spring-boot-autoconfigure} never has to
 * compile against - or order itself relative to - a concrete backend module, preserving that
 * boundary from docs/architecture.md), but construction is deferred to first actual use. A host
 * that genuinely wants File support but forgets to configure a storage backend still fails
 * loudly, with the real cause ({@link StorageBackend} missing) directly in the resulting
 * {@code UnsatisfiedDependencyException} - just deferred to whenever something first asks for a
 * {@code FileService}, instead of at context-refresh time regardless of whether anyone ever will.
 *
 * <p>Ordered {@code before = HibernateJpaAutoConfiguration.class} so {@link File}'s package is
 * folded into the host's entity scan (via {@link EntityScanPackages#register}) before Hibernate
 * builds its {@code EntityManagerFactory} - see
 * {@link FolderPersistenceAutoConfiguration.FolderEntityScanRegistrar} for why this mechanism is
 * used instead of a host-declared {@code @EntityScan}. Also ordered
 * {@code after = DataSourceAutoConfiguration.class} for the same reason
 * {@link FolderPersistenceAutoConfiguration} is: without it, a {@code DataSource} supplied by
 * Spring Boot's own {@code DataSourceAutoConfiguration} (e.g. its embedded-database
 * auto-configuration) might not exist yet when {@link ConditionalOnSingleCandidate} here is
 * evaluated, silently skipping all of File persistence's wiring.
 */
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class, after = DataSourceAutoConfiguration.class)
@ConditionalOnSingleCandidate(DataSource.class)
@Import(FilePersistenceAutoConfiguration.FileEntityScanRegistrar.class)
public class FilePersistenceAutoConfiguration {

    /**
     * Exposes a shared, transaction-aware {@link EntityManager} bean so callers (including
     * {@link #fileService}) can depend on {@link EntityManager} directly rather than reaching
     * for {@code @PersistenceContext} field injection. Backs off cleanly via
     * {@link ConditionalOnMissingBean} if {@link FolderPersistenceAutoConfiguration} (or
     * {@code ApiKeyPersistenceAutoConfiguration}) already registered one -
     * {@link EntityScanPackages#register} accumulates entity packages independently of which
     * autoconfiguration's {@link EntityManager} bean wins.
     */
    @Bean
    @ConditionalOnMissingBean(EntityManager.class)
    public EntityManager entityManager(EntityManagerFactory entityManagerFactory) {
        return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    }

    @Bean
    @Lazy
    public FileService fileService(
            EntityManager entityManager, FolderService folderService, StorageBackend storageBackend) {
        return new FileService(entityManager, folderService, storageBackend);
    }

    /**
     * Folds {@code io.github.bitaron.filemanager.core.file} ({@link File}'s package) into the
     * host's JPA entity scan - see
     * {@link FolderPersistenceAutoConfiguration.FolderEntityScanRegistrar} for why this mechanism
     * is used instead of a host-declared {@code @EntityScan}. {@link EntityScanPackages#register}
     * accumulates package names, so this and Folder's/ApiKey's own registrars all contribute to
     * the same underlying entity scan.
     */
    static class FileEntityScanRegistrar implements ImportBeanDefinitionRegistrar {

        @Override
        public void registerBeanDefinitions(
                AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
            EntityScanPackages.register(registry, "io.github.bitaron.filemanager.core.file");
        }
    }
}
