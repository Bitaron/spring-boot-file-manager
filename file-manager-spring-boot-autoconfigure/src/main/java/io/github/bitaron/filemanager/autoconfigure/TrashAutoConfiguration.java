package io.github.bitaron.filemanager.autoconfigure;

import javax.sql.DataSource;

import io.github.bitaron.filemanager.core.file.FileService;
import io.github.bitaron.filemanager.core.folder.FolderService;
import io.github.bitaron.filemanager.core.trash.TrashService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

/**
 * Embedded Mode wiring for the unified trash bin (issue #37, decision #17's fourth granular
 * service). Unlike {@link FolderPersistenceAutoConfiguration}/{@link FilePersistenceAutoConfiguration}/
 * {@link AccessTokenPersistenceAutoConfiguration}, {@link TrashService} has no entity/persistence
 * seam of its own - it's a pure read composition over {@link FolderService}/{@link FileService},
 * both already entity-scanned by their own autoconfigurations - so this class needs neither an
 * {@code EntityManager} bean nor an {@code EntityScanPackages} registrar, and no ordering relative
 * to {@code HibernateJpaAutoConfiguration}.
 *
 * <p>{@code @ConditionalOnSingleCandidate(DataSource.class)} mirrors {@link
 * AccessTokenPersistenceAutoConfiguration}'s own guard rather than a more direct
 * {@code @ConditionalOnBean({FolderService.class, FileService.class})}: {@link
 * FilePersistenceAutoConfiguration}'s own javadoc documents the well-known cross-module
 * {@code @ConditionalOnBean} ordering pitfall (a bean declared in one autoconfiguration module not
 * being reliably visible yet when another, unordered module's condition is evaluated) - gating on
 * {@link DataSource} instead sidesteps it entirely, since {@link FolderService}/{@link FileService}
 * are themselves always registered exactly when a single {@link DataSource} candidate exists.
 *
 * <p>{@link #trashService} is marked {@link Lazy} for the same reason {@link
 * AccessTokenPersistenceAutoConfiguration#accessTokenService} is: it transitively depends on
 * {@link FileService}, itself {@code @Lazy}-produced to avoid forcing eager resolution of a
 * {@code StorageBackend} that may not be configured in every host.
 */
@AutoConfiguration
@ConditionalOnSingleCandidate(DataSource.class)
public class TrashAutoConfiguration {

    @Bean
    @Lazy
    public TrashService trashService(FolderService folderService, FileService fileService) {
        return new TrashService(folderService, fileService);
    }
}
