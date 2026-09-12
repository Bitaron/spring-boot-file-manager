package io.github.bitaron.filemanager.core.jpa;

import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

/**
 * Builds a plain-JPA {@link EntityManagerFactory} (Hibernate + a fresh, uniquely-named in-memory
 * H2 database per call) for {@code file-manager-core}'s own DAO tests - no Spring context
 * (docs/testing.md). A unique database name per factory keeps test classes that each build their
 * own factory from colliding on the same in-memory instance.
 */
public final class TestEntityManagerFactory {

    private static final String PERSISTENCE_UNIT = "file-manager-core-test";

    private TestEntityManagerFactory() {
    }

    public static EntityManagerFactory create() {
        String jdbcUrl = "jdbc:h2:mem:file-manager-core-test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        return Persistence.createEntityManagerFactory(
                PERSISTENCE_UNIT, Map.of("jakarta.persistence.jdbc.url", jdbcUrl));
    }
}
