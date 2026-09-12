package io.github.bitaron.filemanager.core.apikey;

import java.util.Optional;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.apikey.ApiKeyResolution.Authenticated;
import io.github.bitaron.filemanager.core.apikey.ApiKeyResolution.NotAuthenticated;

/**
 * Resolves a raw, presented ApiKey secret into its Tenant + Actor, before any other lookup
 * happens (`docs/api-design.md`). Standalone Service's trust boundary is exactly what this
 * verifies; Embedded Mode callers that already hold a resolved {@code TenantId}/{@code Actor}
 * don't need this at all - it's the small, optional piece decision #17 carves out for a host that
 * specifically wants to resolve a raw secret using file-manager's own verification logic.
 */
public class ApiKeyResolver {

    private static final NotAuthenticated NOT_AUTHENTICATED = new NotAuthenticated();

    private final ApiKeyLookup apiKeyLookup;

    public ApiKeyResolver(ApiKeyLookup apiKeyLookup) {
        this.apiKeyLookup = apiKeyLookup;
    }

    public ApiKeyResolution resolve(String presentedSecret) {
        if (!ApiKeySecret.isWellFormed(presentedSecret)) {
            return NOT_AUTHENTICATED;
        }

        String keyHash = ApiKeyHasher.hash(presentedSecret);
        Optional<ApiKey> found = apiKeyLookup.findByKeyHash(keyHash);
        if (found.isEmpty()) {
            return NOT_AUTHENTICATED;
        }

        ApiKey apiKey = found.get();
        if (apiKey.isRevoked()) {
            return NOT_AUTHENTICATED;
        }

        return new Authenticated(apiKey.tenantId(), new Actor(apiKey.getId()));
    }
}
