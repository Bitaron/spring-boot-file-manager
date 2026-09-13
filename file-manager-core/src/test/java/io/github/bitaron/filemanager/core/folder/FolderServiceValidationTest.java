package io.github.bitaron.filemanager.core.folder;

import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.tenant.TenantId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests: {@link FolderDao} is faked out with a {@link FolderLookup} that rejects any
 * call, so these exercise only {@link FolderService#create}'s own guard-clause validation - no
 * database, no Spring context (docs/testing.md), mirroring apikey's {@code ApiKeyResolverTest}.
 */
class FolderServiceValidationTest {

    private final FolderService folderService = new FolderService(rejectingLookup());

    @Test
    void rejectsCreateWithNullTenantId() {
        Actor actor = new Actor(UUID.randomUUID());

        assertThatThrownBy(() -> folderService.create(null, actor, "Orphan", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCreateWithNullActor() {
        TenantId tenantId = new TenantId(UUID.randomUUID());

        assertThatThrownBy(() -> folderService.create(tenantId, null, "Orphan", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCreateWithBlankName() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        assertThatThrownBy(() -> folderService.create(tenantId, actor, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCreateWithNullName() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        assertThatThrownBy(() -> folderService.create(tenantId, actor, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Every guard clause above rejects before {@code create} would ever reach the DAO. */
    private static FolderLookup rejectingLookup() {
        return new FolderLookup() {
            @Override
            public Folder findByIdForTenant(UUID id, TenantId tenantId) {
                throw new AssertionError("A guard-clause rejection must never reach the DAO");
            }

            @Override
            public Folder save(Folder folder) {
                throw new AssertionError("A guard-clause rejection must never reach the DAO");
            }
        };
    }
}
