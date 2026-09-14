package io.github.bitaron.filemanager.usageexample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Embedded Mode usage example (issue #12/#66). Deliberately not a web app -
 * no {@code spring-boot-starter-web} dependency - because Embedded Mode itself never opens a
 * socket (docs/architecture.md); this app calls {@code FolderService}/{@code FileService}/
 * {@code TrashService} directly, the same way any host application embedding
 * {@code file-manager-spring-boot-starter} would. {@link EmbeddedModeWalkthrough} runs once at
 * startup and the process then exits - there's no server to keep it alive.
 */
@SpringBootApplication
public class UsageExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsageExampleApplication.class, args);
    }
}
