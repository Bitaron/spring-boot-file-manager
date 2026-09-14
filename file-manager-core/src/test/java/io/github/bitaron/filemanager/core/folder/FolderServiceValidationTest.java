package io.github.bitaron.filemanager.core.folder;

import java.util.List;
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

    @Test
    void rejectsFetchWithNullTenantId() {
        UUID folderId = UUID.randomUUID();

        assertThatThrownBy(() -> folderService.fetch(null, folderId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFetchWithNullFolderId() {
        TenantId tenantId = new TenantId(UUID.randomUUID());

        assertThatThrownBy(() -> folderService.fetch(tenantId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsListChildrenWithNullTenantId() {
        assertThatThrownBy(() -> folderService.listChildren(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPagedListChildrenWithNullTenantId() {
        assertThatThrownBy(() -> folderService.listChildren(null, null, null, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPagedListChildrenWithNonPositiveLimit() {
        TenantId tenantId = new TenantId(UUID.randomUUID());

        assertThatThrownBy(() -> folderService.listChildren(tenantId, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRenameWithNullTenantId() {
        Actor actor = new Actor(UUID.randomUUID());
        UUID folderId = UUID.randomUUID();

        assertThatThrownBy(() -> folderService.rename(null, actor, folderId, "New Name"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRenameWithNullActor() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        UUID folderId = UUID.randomUUID();

        assertThatThrownBy(() -> folderService.rename(tenantId, null, folderId, "New Name"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRenameWithNullFolderId() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        assertThatThrownBy(() -> folderService.rename(tenantId, actor, null, "New Name"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRenameWithBlankName() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID folderId = UUID.randomUUID();

        assertThatThrownBy(() -> folderService.rename(tenantId, actor, folderId, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRenameWithNullName() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID folderId = UUID.randomUUID();

        assertThatThrownBy(() -> folderService.rename(tenantId, actor, folderId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMoveWithNullTenantId() {
        Actor actor = new Actor(UUID.randomUUID());
        UUID folderId = UUID.randomUUID();

        assertThatThrownBy(() -> folderService.move(null, actor, folderId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMoveWithNullActor() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        UUID folderId = UUID.randomUUID();

        assertThatThrownBy(() -> folderService.move(tenantId, null, folderId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMoveWithNullFolderId() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        assertThatThrownBy(() -> folderService.move(tenantId, actor, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A Folder as its own parent is a one-row cycle: ADR 0003's ancestor-trashed check and
     * breadcrumb navigation walk the parent chain at read time, and that walk never terminates
     * against a self-referencing row. Caught here as a pure guard clause - comparing the two ids
     * needs no lookup, unlike full descendant-cycle detection, which would need to walk the chain
     * and isn't asked for by this ticket.
     */
    @Test
    void rejectsMovingAFolderIntoItself() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID folderId = UUID.randomUUID();

        assertThatThrownBy(() -> folderService.move(tenantId, actor, folderId, folderId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTrashWithNullTenantId() {
        Actor actor = new Actor(UUID.randomUUID());
        UUID folderId = UUID.randomUUID();

        assertThatThrownBy(() -> folderService.trash(null, actor, folderId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTrashWithNullActor() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        UUID folderId = UUID.randomUUID();

        assertThatThrownBy(() -> folderService.trash(tenantId, null, folderId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTrashWithNullFolderId() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        assertThatThrownBy(() -> folderService.trash(tenantId, actor, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRestoreWithNullTenantId() {
        Actor actor = new Actor(UUID.randomUUID());
        UUID folderId = UUID.randomUUID();

        assertThatThrownBy(() -> folderService.restore(null, actor, folderId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRestoreWithNullActor() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        UUID folderId = UUID.randomUUID();

        assertThatThrownBy(() -> folderService.restore(tenantId, null, folderId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRestoreWithNullFolderId() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        assertThatThrownBy(() -> folderService.restore(tenantId, actor, null))
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

            @Override
            public List<Folder> findChildrenForTenant(UUID parentFolderId, TenantId tenantId) {
                throw new AssertionError("A guard-clause rejection must never reach the DAO");
            }

            @Override
            public List<Folder> findChildrenForTenant(
                    UUID parentFolderId, TenantId tenantId, UUID afterId, int limit) {
                throw new AssertionError("A guard-clause rejection must never reach the DAO");
            }

            @Override
            public List<Folder> findTrashedForTenant(UUID parentFolderId, TenantId tenantId) {
                throw new AssertionError("A guard-clause rejection must never reach the DAO");
            }
        };
    }
}
