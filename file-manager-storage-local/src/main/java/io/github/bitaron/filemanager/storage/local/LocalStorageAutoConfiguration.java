package io.github.bitaron.filemanager.storage.local;

import java.nio.file.Path;

import io.github.bitaron.filemanager.storage.StorageBackend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Embedded Mode wiring for the local filesystem {@link LocalStorageBackend}, guarded so it only
 * activates when the host opts into it via {@code file-manager.storage.backend=local} (ADR 0002).
 * Self-registered via this module's own
 * {@code src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * - deliberately NOT registered from {@code file-manager-spring-boot-autoconfigure}, which never
 * compiles against a concrete backend.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "file-manager.storage.backend", havingValue = "local")
public class LocalStorageAutoConfiguration {

    /**
     * {@code @ConditionalOnMissingBean(StorageBackend.class)} mirrors
     * {@code FolderPersistenceAutoConfiguration}'s {@code @ConditionalOnMissingBean(EntityManager.class)}
     * pattern, so a host can still supply its own {@link StorageBackend} bean to override this one.
     */
    @Bean
    @ConditionalOnMissingBean(StorageBackend.class)
    public StorageBackend localStorageBackend(
            @Value("${file-manager.storage.local.root-directory}") String rootDirectory) {
        return new LocalStorageBackend(Path.of(rootDirectory));
    }
}
