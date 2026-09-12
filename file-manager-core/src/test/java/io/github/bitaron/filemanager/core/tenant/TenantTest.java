package io.github.bitaron.filemanager.core.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantTest {

    @Test
    void createAssignsAUuidV7IdAndCreationTimestamp() {
        Tenant tenant = Tenant.create("Acme Inc.");

        assertThat(tenant.getId()).isNotNull();
        assertThat(tenant.getId().version()).isEqualTo(7);
        assertThat(tenant.getName()).isEqualTo("Acme Inc.");
        assertThat(tenant.getCreatedAt()).isNotNull();
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> Tenant.create("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullName() {
        assertThatThrownBy(() -> Tenant.create(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
