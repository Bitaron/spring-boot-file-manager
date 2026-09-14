package io.github.bitaron.filemanager.usageexample;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the whole point of this module (issue #12/#66): the app boots, and
 * {@link UsageExampleRunner} runs {@link EmbeddedModeWalkthrough} to completion against a real
 * (embedded) database and {@code StorageBackend} - if a future change to file-manager-core's
 * services breaks any call this walkthrough makes, context startup fails here rather than the
 * drift going unnoticed in docs/library-usage.md's prose.
 */
@SpringBootTest
class UsageExampleApplicationTest {

    @Test
    void bootsAndRunsTheWalkthroughWithoutError() {
        // Context startup alone (via @SpringBootTest) already runs UsageExampleRunner, since
        // Spring Boot invokes every ApplicationRunner bean once the context is fully refreshed.
        // A failure anywhere in EmbeddedModeWalkthrough.run() would fail context startup, and
        // therefore this test - nothing further needs asserting.
    }
}
