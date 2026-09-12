package io.github.bitaron.filemanager.core.apikey;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.tenant.TenantId;

/**
 * The outcome of resolving a presented ApiKey secret. Deliberately has just two shapes -
 * {@link Authenticated} or {@link NotAuthenticated} - with no reason code attached to the latter:
 * a missing, malformed, and revoked key are handled identically by every caller (matching the
 * REST layer's single {@code 401} for all three, ADR 0004), so a distinguishable reason would
 * only exist to be discarded, or worse, leaked.
 */
public sealed interface ApiKeyResolution {

    record Authenticated(TenantId tenantId, Actor actor) implements ApiKeyResolution {
    }

    record NotAuthenticated() implements ApiKeyResolution {
    }
}
