package io.github.bitaron.filemanager.usageexample;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs {@link EmbeddedModeWalkthrough} once, at startup - kept as its own bean (rather than
 * having {@link EmbeddedModeWalkthrough} implement {@link ApplicationRunner} itself) specifically
 * so {@code EmbeddedModeWalkthrough.run()} is invoked from a different bean, through its Spring
 * proxy, which is what makes its {@code @Transactional} annotation actually take effect (see that
 * class's javadoc).
 */
@Component
public class UsageExampleRunner implements ApplicationRunner {

    private final EmbeddedModeWalkthrough walkthrough;

    public UsageExampleRunner(EmbeddedModeWalkthrough walkthrough) {
        this.walkthrough = walkthrough;
    }

    @Override
    public void run(ApplicationArguments args) {
        walkthrough.run();
    }
}
