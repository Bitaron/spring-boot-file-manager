package io.github.bitaron.filemanager.core.apikey;

import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;

/**
 * Hand-written JPQL data access for {@link ApiKey}, talking to a plain {@link EntityManager}
 * rather than a Spring Data JPA repository - core reuses the host's persistence context in
 * Embedded Mode, and registering a {@code JpaRepository} would disable the host's own implicit
 * repository scanning (decision #17).
 */
public class ApiKeyRepository implements ApiKeyLookup {

    private final EntityManager entityManager;

    public ApiKeyRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Direct indexed lookup by {@code key_hash} - the sole query behind ApiKey resolution. */
    @Override
    public Optional<ApiKey> findByKeyHash(String keyHash) {
        TypedQuery<ApiKey> query = entityManager.createQuery(
                "SELECT k FROM ApiKey k WHERE k.keyHash = :keyHash", ApiKey.class);
        query.setParameter("keyHash", keyHash);
        try {
            return Optional.of(query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
}
