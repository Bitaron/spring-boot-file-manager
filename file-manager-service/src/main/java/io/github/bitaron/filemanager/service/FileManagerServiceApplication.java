package io.github.bitaron.filemanager.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Standalone Service: boots the same engine as Embedded Mode (via
 * {@code file-manager-spring-boot-starter}) plus a REST layer on top.
 *
 * <p>Requires the deploying operator to supply {@code spring.datasource.*} properties (or
 * equivalent environment variables) - this class makes no production database choice of its own.
 * Once a {@code DataSource} bean exists, {@code FolderPersistenceAutoConfiguration} and
 * {@code ApiKeyPersistenceAutoConfiguration} (both in {@code file-manager-spring-boot-autoconfigure})
 * activate and expose the {@code FolderService}/{@code ApiKeyResolver} beans this module's
 * controllers and {@code ApiKeyAuthenticationFilter} depend on. The module's own test suite needs
 * no such property - an embedded H2 {@code DataSource} auto-configures itself purely from the
 * driver's presence on the test classpath (docs/testing.md).
 */
@SpringBootApplication
public class FileManagerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileManagerServiceApplication.class, args);
    }
}
