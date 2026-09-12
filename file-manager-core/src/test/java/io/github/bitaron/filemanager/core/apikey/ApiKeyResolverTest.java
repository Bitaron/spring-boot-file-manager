package io.github.bitaron.filemanager.core.apikey;

import java.util.Optional;

import io.github.bitaron.filemanager.core.tenant.Tenant;
import io.github.bitaron.filemanager.core.tenant.TenantId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests: {@link ApiKeyRepository} is faked out with a fixed lookup result, so these
 * exercise only {@link ApiKeyResolver}'s own resolution logic - no database, no Spring context
 * (docs/testing.md).
 */
class ApiKeyResolverTest {

    @Test
    void resolvesAValidKeyToItsTenantAndActor() {
        Tenant tenant = Tenant.create("Acme Inc.");
        String secret = ApiKeySecret.generate().value();
        ApiKey apiKey = ApiKey.issue(new TenantId(tenant.getId()), "CI key", ApiKeyHasher.hash(secret));
        ApiKeyResolver resolver = resolverReturning(apiKey);

        ApiKeyResolution resolution = resolver.resolve(secret);

        assertThat(resolution).isInstanceOf(ApiKeyResolution.Authenticated.class);
        var authenticated = (ApiKeyResolution.Authenticated) resolution;
        assertThat(authenticated.tenantId()).isEqualTo(new TenantId(tenant.getId()));
        assertThat(authenticated.actor().id()).isEqualTo(apiKey.getId());
    }

    @Test
    void treatsAMissingKeyAsNotAuthenticated() {
        ApiKeyResolver resolver = resolverReturning(null);

        ApiKeyResolution resolution = resolver.resolve(ApiKeySecret.generate().value());

        assertThat(resolution).isInstanceOf(ApiKeyResolution.NotAuthenticated.class);
    }

    @Test
    void treatsAMalformedKeyAsNotAuthenticatedWithoutQueryingTheRepository() {
        ApiKeyLookup lookup = keyHash -> {
            throw new AssertionError("A malformed key must never reach the repository");
        };
        ApiKeyResolver resolver = new ApiKeyResolver(lookup);

        ApiKeyResolution resolution = resolver.resolve("not-an-api-key");

        assertThat(resolution).isInstanceOf(ApiKeyResolution.NotAuthenticated.class);
    }

    @Test
    void treatsARevokedKeyAsNotAuthenticated() {
        Tenant tenant = Tenant.create("Acme Inc.");
        String secret = ApiKeySecret.generate().value();
        ApiKey apiKey = ApiKey.issue(new TenantId(tenant.getId()), "CI key", ApiKeyHasher.hash(secret));
        apiKey.revoke();
        ApiKeyResolver resolver = resolverReturning(apiKey);

        ApiKeyResolution resolution = resolver.resolve(secret);

        assertThat(resolution).isInstanceOf(ApiKeyResolution.NotAuthenticated.class);
    }

    private ApiKeyResolver resolverReturning(ApiKey apiKey) {
        ApiKeyLookup lookup = keyHash -> Optional.ofNullable(apiKey);
        return new ApiKeyResolver(lookup);
    }
}
