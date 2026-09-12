package io.github.bitaron.filemanager.core.tenant;

import java.util.UUID;

/**
 * A resolved reference to a {@link Tenant}, the top-level isolation boundary (see {@code
 * CONTEXT.md}). Every cross-Tenant core operation takes a {@code TenantId} as an explicit
 * parameter - never inferred from thread-local/security-context state - so {@code
 * file-manager-core} stays usable outside a web request thread in Embedded Mode (decision #17,
 * `docs/java-conventions.md`).
 */
public record TenantId(UUID id) {

    public TenantId {
        if (id == null) {
            throw new IllegalArgumentException("Tenant id must not be null");
        }
    }
}
