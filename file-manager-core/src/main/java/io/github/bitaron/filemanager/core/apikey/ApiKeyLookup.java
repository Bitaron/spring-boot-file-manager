package io.github.bitaron.filemanager.core.apikey;

import java.util.Optional;

/**
 * The narrow seam {@link ApiKeyResolver} depends on: a single lookup by {@code key_hash}.
 * Separated from {@link ApiKeyRepository}'s concrete, {@code EntityManager}-backed implementation
 * so the resolver's own tests can fake this out with no database and no Spring context
 * (docs/testing.md).
 */
public interface ApiKeyLookup {

    Optional<ApiKey> findByKeyHash(String keyHash);
}
