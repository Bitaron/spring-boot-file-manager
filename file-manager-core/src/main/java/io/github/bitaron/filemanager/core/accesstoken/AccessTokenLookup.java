package io.github.bitaron.filemanager.core.accesstoken;

import java.util.Optional;

/**
 * The narrow seam {@link AccessTokenService} depends on: saving an AccessToken and looking one up
 * by its own token value. No Tenant-scoping parameter, unlike {@code core.file.FileLookup} -
 * redemption ({@link AccessTokenService#redeem}) is unauthenticated, so there is no Tenant to
 * scope by; the token itself is the whole lookup key, globally unique as the primary key (mirrors
 * {@code core.apikey.ApiKeyLookup}'s equivalent global, secret-based lookup shape). Separated from
 * {@code AccessTokenDao}'s concrete, {@code EntityManager}-backed implementation so the service's
 * own tests can fake this out with no database (docs/testing.md).
 */
interface AccessTokenLookup {

    AccessToken save(AccessToken accessToken);

    Optional<AccessToken> findByToken(String token);
}
