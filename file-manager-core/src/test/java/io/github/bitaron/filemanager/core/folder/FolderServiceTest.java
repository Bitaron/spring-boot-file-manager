package io.github.bitaron.filemanager.core.folder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.jpa.TestEntityManagerFactory;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link FolderService} behavior that genuinely needs persistence (create + reload,
 * parent-existence lookup) against a real {@link EntityManager}/Hibernate, backed by an
 * in-memory H2 database - no Spring context involved (docs/testing.md: core's domain rules are
 * tested with no Spring context). Pure guard-clause validation lives in
 * {@link FolderServiceValidationTest} instead, against a fake {@link FolderLookup}.
 */
class FolderServiceTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private FolderService folderService;

    @BeforeAll
    static void openFactory() {
        entityManagerFactory = TestEntityManagerFactory.create();
    }

    @AfterAll
    static void closeFactory() {
        entityManagerFactory.close();
    }

    @BeforeEach
    void openEntityManager() {
        entityManager = entityManagerFactory.createEntityManager();
        folderService = new FolderService(entityManager);
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    void createsTopLevelFolderWithNoParent() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder created = folderService.create(tenantId, actor, "Quarterly Reports", null);
        entityManager.getTransaction().commit();

        // Clear the persistence context so the lookup below hits the database rather than
        // returning the same managed instance still cached in memory.
        entityManager.clear();
        Folder reloaded = entityManager.find(Folder.class, created.getId());

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getParentFolderId()).isNull();
        assertThat(reloaded.tenantId()).isEqualTo(tenantId);
        assertThat(reloaded.getName()).isEqualTo("Quarterly Reports");
    }

    @Test
    void createsNestedFolderWithParentReference() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder parent = folderService.create(tenantId, actor, "Quarterly Reports", null);
        Folder child = folderService.create(tenantId, actor, "2026 Q1", parent.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder reloadedChild = entityManager.find(Folder.class, child.getId());

        assertThat(reloadedChild).isNotNull();
        assertThat(reloadedChild.getParentFolderId()).isEqualTo(parent.getId());
    }

    @Test
    void allowsSiblingFoldersWithSameName() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder first = folderService.create(tenantId, actor, "Invoices", null);
        Folder second = folderService.create(tenantId, actor, "Invoices", null);
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder reloadedFirst = entityManager.find(Folder.class, first.getId());
        Folder reloadedSecond = entityManager.find(Folder.class, second.getId());

        assertThat(reloadedFirst).isNotNull();
        assertThat(reloadedSecond).isNotNull();
        assertThat(reloadedFirst.getId()).isNotEqualTo(reloadedSecond.getId());
        assertThat(reloadedFirst.getName()).isEqualTo("Invoices");
        assertThat(reloadedSecond.getName()).isEqualTo("Invoices");
    }

    @Test
    void rejectsCreateWithNonexistentParent() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID nonexistentParentId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        try {
            assertThatThrownBy(() -> folderService.create(tenantId, actor, "Orphan", nonexistentParentId))
                    .isInstanceOf(FolderNotFoundException.class);
        } finally {
            entityManager.getTransaction().rollback();
        }
    }

    @Test
    void fetchesSingleFolderMetadataById() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder created = folderService.create(tenantId, actor, "Quarterly Reports", null);
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder fetched = folderService.fetch(tenantId, created.getId());

        assertThat(fetched).isNotNull();
        assertThat(fetched.getId()).isEqualTo(created.getId());
        assertThat(fetched.getName()).isEqualTo("Quarterly Reports");
    }

    @Test
    void fetchReturnsNullWhenFolderBelongsToAnotherTenant() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        TenantId otherTenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder created = folderService.create(tenantId, actor, "Quarterly Reports", null);
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder fetched = folderService.fetch(otherTenantId, created.getId());

        assertThat(fetched).isNull();
    }

    @Test
    void listsImmediateChildrenOfAFolder() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder parent = folderService.create(tenantId, actor, "Quarterly Reports", null);
        Folder child1 = folderService.create(tenantId, actor, "2026 Q1", parent.getId());
        Folder child2 = folderService.create(tenantId, actor, "2026 Q2", parent.getId());
        // A top-level Folder, and another Tenant's child of the same parent id are noise this
        // list must exclude.
        folderService.create(tenantId, actor, "Unrelated Top-Level Folder", null);
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<Folder> children = folderService.listChildren(tenantId, parent.getId());

        assertThat(children).extracting(Folder::getId)
                .containsExactlyInAnyOrder(child1.getId(), child2.getId());
    }

    @Test
    void listsTopLevelFoldersWhenParentIdOmitted() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder topLevel1 = folderService.create(tenantId, actor, "Quarterly Reports", null);
        Folder topLevel2 = folderService.create(tenantId, actor, "Invoices", null);
        // A nested Folder is noise this top-level list must exclude.
        folderService.create(tenantId, actor, "2026 Q1", topLevel1.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<Folder> children = folderService.listChildren(tenantId, null);

        assertThat(children).extracting(Folder::getId)
                .containsExactlyInAnyOrder(topLevel1.getId(), topLevel2.getId());
    }

    @Test
    void listChildrenIsScopedToTenant() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        TenantId otherTenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        folderService.create(tenantId, actor, "Quarterly Reports", null);
        folderService.create(otherTenantId, actor, "Someone Else's Folder", null);
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<Folder> otherTenantTopLevel = folderService.listChildren(otherTenantId, null);

        assertThat(otherTenantTopLevel).extracting(Folder::getName)
                .containsExactly("Someone Else's Folder");
    }

    /**
     * UUIDv7 only orders by millisecond timestamp (see {@code UuidV7}) - Folders created within
     * the same millisecond have no guaranteed relative order, so these tests derive "the" id
     * order from an unbounded page (the same {@code ORDER BY id ASC} query under test) instead of
     * assuming creation order, to avoid flaking on a fast test run.
     */
    @Test
    void pagedListChildrenReturnsAtMostLimitFoldersInIdOrder() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        folderService.create(tenantId, actor, "A", null);
        folderService.create(tenantId, actor, "B", null);
        folderService.create(tenantId, actor, "C", null);
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<Folder> idOrder = folderService.listChildren(tenantId, null, null, 50);
        List<Folder> firstPage = folderService.listChildren(tenantId, null, null, 2);

        assertThat(firstPage).extracting(Folder::getId)
                .containsExactlyElementsOf(
                        idOrder.stream().limit(2).map(Folder::getId).toList());
    }

    @Test
    void pagedListChildrenResumesAfterTheGivenCursor() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        folderService.create(tenantId, actor, "A", null);
        folderService.create(tenantId, actor, "B", null);
        folderService.create(tenantId, actor, "C", null);
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<Folder> idOrder = folderService.listChildren(tenantId, null, null, 50);
        UUID cursor = idOrder.get(0).getId();

        List<Folder> nextPage = folderService.listChildren(tenantId, null, cursor, 50);

        assertThat(nextPage).extracting(Folder::getId)
                .containsExactlyElementsOf(
                        idOrder.stream().skip(1).map(Folder::getId).toList());
    }

    @Test
    void renamesFolderAndRecordsTheRenamingActor() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor creator = new Actor(UUID.randomUUID());
        Actor renamer = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder created = folderService.create(tenantId, creator, "Quarterly Reports", null);
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.rename(tenantId, renamer, created.getId(), "Annual Reports");
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder reloaded = entityManager.find(Folder.class, created.getId());

        assertThat(reloaded.getName()).isEqualTo("Annual Reports");
        assertThat(reloaded.lastModifiedBy()).isEqualTo(renamer);
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(reloaded.getCreatedAt());
    }

    @Test
    void rejectsRenameOfNonexistentFolder() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID nonexistentFolderId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        try {
            assertThatThrownBy(() -> folderService.rename(tenantId, actor, nonexistentFolderId, "New Name"))
                    .isInstanceOf(FolderNotFoundException.class);
        } finally {
            entityManager.getTransaction().rollback();
        }
    }

    @Test
    void rejectsRenameOfFolderBelongingToAnotherTenant() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        TenantId otherTenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder created = folderService.create(tenantId, actor, "Quarterly Reports", null);
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        try {
            assertThatThrownBy(() -> folderService.rename(otherTenantId, actor, created.getId(), "New Name"))
                    .isInstanceOf(FolderNotFoundException.class);
        } finally {
            entityManager.getTransaction().rollback();
        }
    }

    @Test
    void movesFolderToADifferentParentAsASingleRowUpdate() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder oldParent = folderService.create(tenantId, actor, "2026 Q1", null);
        Folder newParent = folderService.create(tenantId, actor, "2026 Q2", null);
        Folder child = folderService.create(tenantId, actor, "Invoice.pdf", oldParent.getId());
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.move(tenantId, actor, child.getId(), newParent.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder reloaded = entityManager.find(Folder.class, child.getId());

        assertThat(reloaded.getParentFolderId()).isEqualTo(newParent.getId());
    }

    @Test
    void movesFolderToTopLevelAsASingleRowUpdate() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder parent = folderService.create(tenantId, actor, "Quarterly Reports", null);
        Folder child = folderService.create(tenantId, actor, "2026 Q1", parent.getId());
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.move(tenantId, actor, child.getId(), null);
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder reloaded = entityManager.find(Folder.class, child.getId());

        assertThat(reloaded.getParentFolderId()).isNull();
    }

    @Test
    void rejectsMoveToNonexistentParent() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID nonexistentParentId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        Folder folder = folderService.create(tenantId, actor, "Quarterly Reports", null);
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        try {
            assertThatThrownBy(() -> folderService.move(tenantId, actor, folder.getId(), nonexistentParentId))
                    .isInstanceOf(FolderNotFoundException.class);
        } finally {
            entityManager.getTransaction().rollback();
        }
    }

    @Test
    void rejectsMoveOfNonexistentFolder() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID nonexistentFolderId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        try {
            assertThatThrownBy(() -> folderService.move(tenantId, actor, nonexistentFolderId, null))
                    .isInstanceOf(FolderNotFoundException.class);
        } finally {
            entityManager.getTransaction().rollback();
        }
    }

    @Test
    void allowsRenamingToAnExistingSiblingsName() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        folderService.create(tenantId, actor, "Invoices", null);
        Folder second = folderService.create(tenantId, actor, "Receipts", null);
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        Folder renamed = folderService.rename(tenantId, actor, second.getId(), "Invoices");
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder reloaded = entityManager.find(Folder.class, renamed.getId());

        assertThat(reloaded.getName()).isEqualTo("Invoices");
    }

    @Test
    void allowsMovingAFolderNextToAnExistingSameNamedSibling() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder destination = folderService.create(tenantId, actor, "Quarterly Reports", null);
        folderService.create(tenantId, actor, "Invoices", destination.getId());
        Folder moving = folderService.create(tenantId, actor, "Invoices", null);
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.move(tenantId, actor, moving.getId(), destination.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<Folder> children = folderService.listChildren(tenantId, destination.getId());

        assertThat(children).extracting(Folder::getName)
                .containsExactlyInAnyOrder("Invoices", "Invoices");
    }

    @Test
    void trashSetsTheFoldersOwnTrashedAtAndTrashedBy() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor creator = new Actor(UUID.randomUUID());
        Actor trasher = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder created = folderService.create(tenantId, creator, "Quarterly Reports", null);
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, trasher, created.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder reloaded = entityManager.find(Folder.class, created.getId());

        assertThat(reloaded.getTrashedAt()).isNotNull();
        assertThat(reloaded.trashedBy()).isEqualTo(trasher);
    }

    /**
     * Trashing an already-trashed Folder must be a full no-op (issue #37): the second call, made
     * by a different Actor, must not overwrite the first call's {@code trashedAt}/
     * {@code trashedBy}, nor bump {@code updatedAt}/{@code lastModifiedBy} - a true no-op changes
     * nothing, including "last modified" bookkeeping, otherwise a client polling
     * {@code updatedAt} would see a spurious change for a call that changed nothing.
     */
    @Test
    void trashingAnAlreadyTrashedFolderIsANoOp() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor creator = new Actor(UUID.randomUUID());
        Actor firstTrasher = new Actor(UUID.randomUUID());
        Actor secondTrasher = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder created = folderService.create(tenantId, creator, "Quarterly Reports", null);
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, firstTrasher, created.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder afterFirstTrash = entityManager.find(Folder.class, created.getId());
        Instant trashedAtAfterFirstTrash = afterFirstTrash.getTrashedAt();
        Instant updatedAtAfterFirstTrash = afterFirstTrash.getUpdatedAt();
        Actor lastModifiedByAfterFirstTrash = afterFirstTrash.lastModifiedBy();

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, secondTrasher, created.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder afterSecondTrash = entityManager.find(Folder.class, created.getId());

        assertThat(afterSecondTrash.getTrashedAt()).isEqualTo(trashedAtAfterFirstTrash);
        assertThat(afterSecondTrash.trashedBy()).isEqualTo(firstTrasher);
        assertThat(afterSecondTrash.getUpdatedAt()).isEqualTo(updatedAtAfterFirstTrash);
        assertThat(afterSecondTrash.lastModifiedBy()).isEqualTo(lastModifiedByAfterFirstTrash);
    }

    @Test
    void restoreClearsTheFoldersOwnTrashedState() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor creator = new Actor(UUID.randomUUID());
        Actor trasher = new Actor(UUID.randomUUID());
        Actor restorer = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder created = folderService.create(tenantId, creator, "Quarterly Reports", null);
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, trasher, created.getId());
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.restore(tenantId, restorer, created.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder reloaded = entityManager.find(Folder.class, created.getId());

        assertThat(reloaded.getTrashedAt()).isNull();
        assertThat(reloaded.trashedBy()).isNull();
    }

    /**
     * Restoring an already-active (never-trashed) Folder must be a full no-op (issue #37,
     * symmetric with {@link #trashingAnAlreadyTrashedFolderIsANoOp}): nothing changes, including
     * {@code updatedAt}/{@code lastModifiedBy} - a client polling {@code updatedAt} shouldn't see
     * a spurious change for a call that changed nothing.
     */
    @Test
    void restoringAnAlreadyActiveFolderIsANoOp() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor creator = new Actor(UUID.randomUUID());
        Actor restorer = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder created = folderService.create(tenantId, creator, "Quarterly Reports", null);
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder beforeRestore = entityManager.find(Folder.class, created.getId());
        Instant updatedAtBeforeRestore = beforeRestore.getUpdatedAt();
        Actor lastModifiedByBeforeRestore = beforeRestore.lastModifiedBy();

        entityManager.getTransaction().begin();
        folderService.restore(tenantId, restorer, created.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        Folder afterRestore = entityManager.find(Folder.class, created.getId());

        assertThat(afterRestore.getTrashedAt()).isNull();
        assertThat(afterRestore.trashedBy()).isNull();
        assertThat(afterRestore.getUpdatedAt()).isEqualTo(updatedAtBeforeRestore);
        assertThat(afterRestore.lastModifiedBy()).isEqualTo(lastModifiedByBeforeRestore);
    }

    @Test
    void rejectsTrashOfNonexistentFolder() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID nonexistentFolderId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        try {
            assertThatThrownBy(() -> folderService.trash(tenantId, actor, nonexistentFolderId))
                    .isInstanceOf(FolderNotFoundException.class);
        } finally {
            entityManager.getTransaction().rollback();
        }
    }

    @Test
    void rejectsRestoreOfNonexistentFolder() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID nonexistentFolderId = UUID.randomUUID();

        entityManager.getTransaction().begin();
        try {
            assertThatThrownBy(() -> folderService.restore(tenantId, actor, nonexistentFolderId))
                    .isInstanceOf(FolderNotFoundException.class);
        } finally {
            entityManager.getTransaction().rollback();
        }
    }

    /**
     * Ordinary listings silently exclude trashed items, no filter flag needed (issue #37, ADR
     * 0004) - the cheap, per-row half of the exclusion rule: a child's own {@code trashedAt} being
     * set is enough to exclude it, with no ancestor walk needed.
     */
    @Test
    void listChildrenExcludesAChildWithItsOwnTrashedAtSet() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder parent = folderService.create(tenantId, actor, "Quarterly Reports", null);
        Folder trashedChild = folderService.create(tenantId, actor, "Old Draft", parent.getId());
        Folder activeChild = folderService.create(tenantId, actor, "Final", parent.getId());
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, actor, trashedChild.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<Folder> children = folderService.listChildren(tenantId, parent.getId());

        assertThat(children).extracting(Folder::getId).containsExactly(activeChild.getId());
    }

    /**
     * Trashing a Folder makes every descendant disappear from ordinary listings, with no bulk
     * write across descendants (issue #37, decision #16) - listing a trashed Folder's own
     * children returns empty because the Folder itself is effectively trashed, even though the
     * child row's own {@code trashedAt} was never touched.
     */
    @Test
    void listChildrenReturnsEmptyWhenTheParentItselfIsTrashed() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder parent = folderService.create(tenantId, actor, "Quarterly Reports", null);
        Folder child = folderService.create(tenantId, actor, "2026 Q1", parent.getId());
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, actor, parent.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<Folder> children = folderService.listChildren(tenantId, parent.getId());

        assertThat(children).isEmpty();

        // No bulk write across descendants: the child's own row must be untouched by trashing
        // its parent.
        Folder reloadedChild = entityManager.find(Folder.class, child.getId());
        assertThat(reloadedChild.getTrashedAt()).isNull();
    }

    /**
     * The "effectively trashed" walk goes beyond the immediate parent (issue #37, decision #16):
     * in a 3-level hierarchy A -&gt; B -&gt; C, trashing A must still empty out B's own child
     * listing (i.e. C disappears), proving the walk doesn't stop after one hop up from B.
     */
    @Test
    void listChildrenReturnsEmptyWhenAnAncestorBeyondTheImmediateParentIsTrashed() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder a = folderService.create(tenantId, actor, "A", null);
        Folder b = folderService.create(tenantId, actor, "B", a.getId());
        folderService.create(tenantId, actor, "C", b.getId());
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, actor, a.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<Folder> childrenOfB = folderService.listChildren(tenantId, b.getId());

        assertThat(childrenOfB).isEmpty();
    }

    /**
     * Restoring a single trashed item works independently of anything else trashed alongside it
     * (issue #37, decision #16): a descendant trashed independently before its ancestor was
     * trashed stays trashed after the ancestor is restored - A -&gt; B, trash B (its own flag),
     * then trash A, then restore A. B's own flag was never touched by A's trash/restore cycle, so
     * B must still be excluded from A's child listing.
     */
    @Test
    void restoringAnAncestorDoesNotResurrectADescendantTrashedIndependently() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder a = folderService.create(tenantId, actor, "A", null);
        Folder b = folderService.create(tenantId, actor, "B", a.getId());
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, actor, b.getId());
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, actor, a.getId());
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.restore(tenantId, actor, a.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<Folder> childrenOfA = folderService.listChildren(tenantId, a.getId());

        assertThat(childrenOfA).isEmpty();
    }

    /**
     * {@code move} only rejects a Folder becoming its own <em>direct</em> parent
     * ({@code folderId.equals(parentFolderId)}), not an indirect cycle - two {@code move} calls
     * can still produce one (A moved under B, B already under A). {@code isTrashed}'s parent-chain
     * walk must not infinite-loop if it ever encounters such a cycle (code-review finding, issue
     * #37): this asserts the walk terminates (returns, rather than {@code StackOverflowError}),
     * not any particular trashed/not-trashed answer for a state that's already a data-integrity
     * anomaly by the time it's reached.
     */
    @Test
    void isTrashedTerminatesEvenIfTheParentChainContainsACycle() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder a = folderService.create(tenantId, actor, "A", null);
        Folder b = folderService.create(tenantId, actor, "B", a.getId());
        entityManager.getTransaction().commit();

        // A <-> B: move() only guards against a Folder being its own direct parent, so this
        // indirect 2-cycle is currently reachable through the public API.
        entityManager.getTransaction().begin();
        folderService.move(tenantId, actor, a.getId(), b.getId());
        entityManager.getTransaction().commit();

        assertThatCode(() -> folderService.isTrashed(tenantId, a.getId())).doesNotThrowAnyException();
        assertThatCode(() -> folderService.isTrashed(tenantId, b.getId())).doesNotThrowAnyException();
    }

    /**
     * {@code listTrashed} is the trash bin's own query (issue #37): unlike {@code listChildren}'s
     * exclusion rule, it returns only items with their own {@code trashedAt} set - scoped to
     * direct children of a given parent. An untrashed sibling, and a Folder trashed under a
     * completely different parent, are both noise this list must exclude.
     */
    @Test
    void listTrashedScopedToParentReturnsOnlyDirectChildrenWithTheirOwnTrashedAtSet() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder parent = folderService.create(tenantId, actor, "Quarterly Reports", null);
        Folder trashedChild = folderService.create(tenantId, actor, "Old Draft", parent.getId());
        folderService.create(tenantId, actor, "Final", parent.getId());
        Folder otherParent = folderService.create(tenantId, actor, "Unrelated Parent", null);
        Folder trashedUnderOtherParent =
                folderService.create(tenantId, actor, "Old Draft Elsewhere", otherParent.getId());
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, actor, trashedChild.getId());
        folderService.trash(tenantId, actor, trashedUnderOtherParent.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<Folder> trashed = folderService.listTrashed(tenantId, parent.getId());

        assertThat(trashed).extracting(Folder::getId).containsExactly(trashedChild.getId());
    }

    /**
     * With no {@code parentFolderId}, {@code listTrashed} is a flat, tenant-wide scan (issue #37)
     * - it finds every explicitly-trashed Folder regardless of nesting depth, not just top-level
     * ones, and no parent filter is applied at all.
     */
    @Test
    void listTrashedWithNullParentReturnsEveryTrashedFolderTenantWideRegardlessOfNestingDepth() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        entityManager.getTransaction().begin();
        Folder trashedTopLevel = folderService.create(tenantId, actor, "Old Top-Level", null);
        Folder a = folderService.create(tenantId, actor, "A", null);
        Folder b = folderService.create(tenantId, actor, "B", a.getId());
        Folder trashedNested = folderService.create(tenantId, actor, "C", b.getId());
        folderService.create(tenantId, actor, "Untouched Top-Level", null);
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        folderService.trash(tenantId, actor, trashedTopLevel.getId());
        folderService.trash(tenantId, actor, trashedNested.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        List<Folder> trashed = folderService.listTrashed(tenantId, null);

        assertThat(trashed).extracting(Folder::getId)
                .containsExactlyInAnyOrder(trashedTopLevel.getId(), trashedNested.getId());
    }
}
