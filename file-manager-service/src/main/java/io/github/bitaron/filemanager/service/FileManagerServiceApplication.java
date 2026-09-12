package io.github.bitaron.filemanager.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * Entry point for the Standalone Service: boots the same engine as Embedded Mode (via
 * {@code file-manager-spring-boot-starter}) plus a REST layer on top.
 *
 * <p>{@code exclude = DataSourceAutoConfiguration.class}: a stopgap until the Standalone Service
 * wires its own {@code DataSource} (a later issue). Without it, Spring Boot's stock
 * {@code DataSourceAutoConfiguration} - now reachable transitively via
 * {@code file-manager-spring-boot-starter} pulling in {@code spring-boot-starter-data-jpa} for
 * Folder persistence (issue #41) - tries to build a pooled (Hikari) {@code DataSource} from
 * unset properties and fails to boot. Folder persistence's own autoconfigure
 * ({@code FolderPersistenceAutoConfiguration}) already backs off cleanly with no {@code
 * DataSource} bean present; this exclusion just stops Spring Boot's unrelated stock JDBC
 * autoconfiguration from forcing one into existence.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class FileManagerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileManagerServiceApplication.class, args);
    }
}
