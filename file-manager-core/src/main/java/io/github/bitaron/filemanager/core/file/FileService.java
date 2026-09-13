package io.github.bitaron.filemanager.core.file;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.folder.FolderNotFoundException;
import io.github.bitaron.filemanager.core.folder.FolderService;
import io.github.bitaron.filemanager.core.id.UuidV7;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import io.github.bitaron.filemanager.storage.StorageBackend;
import jakarta.persistence.EntityManager;

/**
 * Domain operations on {@link File} (docs/architecture.md's "service interfaces, not entity
 * setters" rule). Delegates persistence mechanics to a {@link FileLookup} and content storage to a
 * {@link StorageBackend}, mirroring {@code core.folder.FolderService}.
 */
public class FileService {

    private final FileLookup fileLookup;
    private final FolderService folderService;
    private final StorageBackend storageBackend;

    public FileService(EntityManager entityManager, FolderService folderService, StorageBackend storageBackend) {
        this(new FileDao(entityManager), folderService, storageBackend);
    }

    FileService(FileLookup fileLookup, FolderService folderService, StorageBackend storageBackend) {
        this.fileLookup = fileLookup;
        this.folderService = folderService;
        this.storageBackend = storageBackend;
    }

    /**
     * Uploads a new File into {@code folderId}, owned by {@code actor}. A {@code null}
     * {@code visibility} defaults to {@link Visibility#PRIVATE} (acceptance criterion for issue
     * #33) - defaulted here, not in the REST layer, so Embedded Mode callers get it too.
     *
     * <p>Storage happens before persistence: {@code storageBackend.store} is called first, and
     * only its returned reference is persisted - so a storage failure never leaves an orphaned
     * metadata row. The reverse failure (storage succeeds, the subsequent persist doesn't) is
     * guarded too: this method deletes the just-stored content before propagating the persistence
     * failure, so a failed upload never leaves an orphaned blob behind either.
     *
     * <p>The File's own freshly-generated id - not the caller-supplied {@code name} - is used as
     * the {@code StorageBackend} key. {@code name} is caller-controlled and need not be unique
     * (ADR 0003: no uniqueness constraint on sibling names), so using it as a storage key would let
     * two Files collide on the same stored path; the local filesystem backend's
     * {@code REPLACE_EXISTING} write would silently overwrite one File's content with another's.
     * The id is generated up front specifically so it's available for this before the {@link File}
     * row itself exists.
     *
     * @throws IllegalArgumentException if {@code tenantId}, {@code actor}, {@code name}, or
     *     {@code contentType} is missing/blank
     * @throws FolderNotFoundException if no Folder with {@code folderId} exists for this Tenant
     */
    public File upload(TenantId tenantId, Actor actor, UUID folderId, String name,
            Visibility visibility, InputStream content, long contentLength, String contentType) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be null or blank");
        }
        if (folderService.fetch(tenantId, folderId) == null) {
            throw new FolderNotFoundException("No Folder with id " + folderId + " exists for this Tenant");
        }
        Visibility resolvedVisibility = visibility == null ? Visibility.PRIVATE : visibility;
        UUID id = UuidV7.randomUuid();
        String storageReference = storageBackend.store(id.toString(), content, contentLength, contentType);
        File file = new File(id, tenantId, folderId, name, contentLength, contentType,
                resolvedVisibility, storageReference, actor, Instant.now());
        try {
            return fileLookup.save(file);
        } catch (RuntimeException e) {
            storageBackend.delete(storageReference);
            throw e;
        }
    }

    /**
     * Fetches a single File's metadata by id, scoped to {@code tenantId}. Mirrors
     * {@code FolderService.fetch}'s "returns {@code null}" contract - no other work is expected
     * of a caller between resolving the File and using it, so a {@code null} return keeps this
     * symmetrical with the Folder equivalent.
     *
     * @return the File, or {@code null} if no File with that id exists for this Tenant
     */
    public File fetch(TenantId tenantId, UUID fileId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (fileId == null) {
            throw new IllegalArgumentException("fileId must not be null");
        }
        return fileLookup.findByIdForTenant(fileId, tenantId);
    }

    /**
     * Fetches a File's metadata together with a live content stream from the configured
     * {@link StorageBackend}, scoped to {@code tenantId}.
     *
     * <p>Unlike {@link #fetch}, this throws rather than returning {@code null} on a missing File:
     * every caller of this method (the REST download endpoint, today) has nothing useful to do
     * with a {@code null} beyond immediately producing a 404, so throwing here is simpler than
     * pushing that null-check onto every caller (a deliberate, one-off deviation from
     * {@code fetch}'s convention - not a mix of styles within this method itself).
     *
     * <p>This is the Secure Access path (see the glossary in {@code CONTEXT.md}): a {@code PUBLIC}
     * File is never served through it - {@code CONTEXT.md}'s "a Public File is never fetched via a
     * Secure Access call" - so it 404s exactly as if the File didn't exist, rather than leaking
     * that a Public File exists under this id via a different error. Non-secure Access instead goes
     * through {@link #fetchContentForNonSecureAccess}.
     *
     * @throws FileNotFoundException if no File with {@code fileId} exists for this Tenant, or it
     *     does but its {@link Visibility} is {@code PUBLIC}
     */
    public FileContent fetchContent(TenantId tenantId, UUID fileId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (fileId == null) {
            throw new IllegalArgumentException("fileId must not be null");
        }
        File file = fileLookup.findByIdForTenant(fileId, tenantId);
        if (file == null) {
            throw new FileNotFoundException("No File with id " + fileId + " exists for this Tenant");
        }
        if (file.getVisibility() == Visibility.PUBLIC) {
            throw new FileNotFoundException("No File with id " + fileId + " exists for this Tenant");
        }
        InputStream content = storageBackend.retrieve(file.getStorageReference());
        return new FileContent(file, content);
    }

    /**
     * Fetches a File's metadata together with a live content stream from the configured
     * {@link StorageBackend}, scoped to {@code tenantId} - <b>without</b> the Secure Access
     * Visibility gate {@link #fetchContent} enforces.
     *
     * <p>This exists solely for Non-secure Access (AccessToken redemption, see the glossary in
     * {@code CONTEXT.md}): by the time {@code core.accesstoken.AccessTokenService#redeem} calls
     * this method, it has already independently proven both that the File is {@code Public}
     * (checked once, at mint time) and that a live, non-expired AccessToken exists for it - so
     * gating on Visibility again here would be redundant, not protective. This method must not be
     * called from anywhere else: doing so would silently reopen Secure Access's Visibility gate for
     * that caller. {@code core.accesstoken} is a different package from {@code core.file}, so this
     * method must be {@code public} - the name and this javadoc are how its narrow, single intended
     * caller is documented, since Java can't narrow the access modifier itself.
     *
     * @throws FileNotFoundException if no File with {@code fileId} exists for this Tenant
     */
    public FileContent fetchContentForNonSecureAccess(TenantId tenantId, UUID fileId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (fileId == null) {
            throw new IllegalArgumentException("fileId must not be null");
        }
        File file = fileLookup.findByIdForTenant(fileId, tenantId);
        if (file == null) {
            throw new FileNotFoundException("No File with id " + fileId + " exists for this Tenant");
        }
        InputStream content = storageBackend.retrieve(file.getStorageReference());
        return new FileContent(file, content);
    }

    /**
     * Lists a Folder's Files, scoped to {@code tenantId}.
     *
     * @param parentFolderId the parent Folder's id - never {@code null}, unlike Folder's own
     *     {@code parentFolderId} (ADR 0003: every File belongs to exactly one Folder)
     * @throws IllegalArgumentException if {@code tenantId} is missing
     */
    public List<File> listFiles(TenantId tenantId, UUID parentFolderId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        return fileLookup.findByParentFolderForTenant(parentFolderId, tenantId);
    }

    /**
     * Lists a Folder's Files one page at a time, scoped to {@code tenantId} - the seam the
     * Standalone Service's cursor pagination is built on (ADR 0004: cursor pagination over the
     * already-time-ordered UUIDv7 PK), mirroring {@code FolderService#listChildren(TenantId, UUID,
     * UUID, int)}. Callers ask for one extra row past {@code limit} to learn whether a next page
     * exists, the same convention {@code afterId} feeds back in as the next request's cursor.
     *
     * @param parentFolderId the parent Folder's id - never {@code null}
     * @param afterId list Files whose id sorts after this one, or {@code null} to start from the
     *     beginning
     * @param limit the maximum number of Files to return
     * @throws IllegalArgumentException if {@code tenantId} is missing or {@code limit} is not
     *     positive
     */
    public List<File> listFiles(TenantId tenantId, UUID parentFolderId, UUID afterId, int limit) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return fileLookup.findByParentFolderForTenant(parentFolderId, tenantId, afterId, limit);
    }

    /**
     * Renames a File in place - a single-row update (ADR 0003/0004). Duplicate sibling names stay
     * legal (ADR 0003), so no uniqueness check is performed. Mirrors
     * {@code FolderService#rename} exactly.
     *
     * @throws IllegalArgumentException if {@code tenantId}, {@code actor}, or {@code fileId} is
     *     missing, or {@code name} is missing/blank
     * @throws FileNotFoundException if no File with {@code fileId} exists for this Tenant
     */
    public File rename(TenantId tenantId, Actor actor, UUID fileId, String name) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (fileId == null) {
            throw new IllegalArgumentException("fileId must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        File file = fileLookup.findByIdForTenant(fileId, tenantId);
        if (file == null) {
            throw new FileNotFoundException("No File with id " + fileId + " exists for this Tenant");
        }
        file.rename(name, actor, Instant.now());
        return fileLookup.save(file);
    }

    /**
     * Moves a File to a different parent Folder - a single-row update (ADR 0003/0004).
     *
     * <p>Unlike {@code FolderService#move}, {@code parentFolderId} is itself required: a File can
     * never be top-level (ADR 0003: every File belongs to exactly one Folder), so there is no
     * "move to top-level" case here. There is likewise no self-parent check - a File's id and a
     * Folder's id are different entity-id types, so a File can never equal its own parent.
     *
     * @throws IllegalArgumentException if {@code tenantId}, {@code actor}, {@code fileId}, or
     *     {@code parentFolderId} is missing
     * @throws FileNotFoundException if no File with {@code fileId} exists for this Tenant
     * @throws FolderNotFoundException if no Folder with {@code parentFolderId} exists for this
     *     Tenant
     */
    public File move(TenantId tenantId, Actor actor, UUID fileId, UUID parentFolderId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (fileId == null) {
            throw new IllegalArgumentException("fileId must not be null");
        }
        if (parentFolderId == null) {
            throw new IllegalArgumentException("parentFolderId must not be null");
        }
        File file = fileLookup.findByIdForTenant(fileId, tenantId);
        if (file == null) {
            throw new FileNotFoundException("No File with id " + fileId + " exists for this Tenant");
        }
        if (folderService.fetch(tenantId, parentFolderId) == null) {
            throw new FolderNotFoundException(
                    "No Folder with id " + parentFolderId + " exists for this Tenant");
        }
        file.moveTo(parentFolderId, actor, Instant.now());
        return fileLookup.save(file);
    }
}
