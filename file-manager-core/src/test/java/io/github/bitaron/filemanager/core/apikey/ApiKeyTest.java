package io.github.bitaron.filemanager.core.apikey;

import io.github.bitaron.filemanager.core.tenant.Tenant;
import io.github.bitaron.filemanager.core.tenant.TenantId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyTest {

    private final TenantId tenantId = new TenantId(Tenant.create("Acme Inc.").getId());

    @Test
    void issueAssignsAUuidV7IdAndStartsUnrevoked() {
        String keyHash = ApiKeyHasher.hash(ApiKeySecret.generate().value());

        ApiKey apiKey = ApiKey.issue(tenantId, "CI key", keyHash);

        assertThat(apiKey.getId()).isNotNull();
        assertThat(apiKey.getId().version()).isEqualTo(7);
        assertThat(apiKey.tenantId()).isEqualTo(tenantId);
        assertThat(apiKey.getLabel()).isEqualTo("CI key");
        assertThat(apiKey.getKeyHash()).isEqualTo(keyHash);
        assertThat(apiKey.isRevoked()).isFalse();
        assertThat(apiKey.getRevokedAt()).isNull();
    }

    @Test
    void issueAllowsANullLabel() {
        ApiKey apiKey = ApiKey.issue(tenantId, null, ApiKeyHasher.hash(ApiKeySecret.generate().value()));

        assertThat(apiKey.getLabel()).isNull();
    }

    @Test
    void issueRejectsANullTenantId() {
        assertThatThrownBy(() -> ApiKey.issue(null, "label", ApiKeyHasher.hash("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issueRejectsAKeyHashOfTheWrongLength() {
        assertThatThrownBy(() -> ApiKey.issue(tenantId, "label", "not-a-sha256-hash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokeSetsRevokedAtAndNeverClearsIt() {
        ApiKey apiKey = ApiKey.issue(tenantId, "CI key", ApiKeyHasher.hash(ApiKeySecret.generate().value()));

        apiKey.revoke();
        var firstRevocation = apiKey.getRevokedAt();
        apiKey.revoke();

        assertThat(apiKey.isRevoked()).isTrue();
        assertThat(apiKey.getRevokedAt()).isEqualTo(firstRevocation);
    }
}
