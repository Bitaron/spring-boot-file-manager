package io.github.bitaron.filemanager.core.accesstoken;

import java.util.Optional;

import jakarta.persistence.EntityManager;

/**
 * Persistence mechanics for {@link AccessToken}, isolated from {@link AccessTokenService}'s
 * business rules (decision: file-manager-core's persistence seam is a plain {@link EntityManager},
 * no Spring Data repository - docs/architecture.md), mirroring {@code core.file.FileDao}.
 */
class AccessTokenDao implements AccessTokenLookup {

    private final EntityManager entityManager;

    AccessTokenDao(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public AccessToken save(AccessToken accessToken) {
        entityManager.persist(accessToken);
        return accessToken;
    }

    @Override
    public Optional<AccessToken> findByToken(String token) {
        return Optional.ofNullable(entityManager.find(AccessToken.class, token));
    }
}
