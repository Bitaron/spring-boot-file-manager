package io.github.bitaron.filemanager.service.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import io.github.bitaron.filemanager.api.file.FileResponse;
import io.github.bitaron.filemanager.api.folder.CreateFolderRequest;
import io.github.bitaron.filemanager.api.folder.FolderResponse;
import io.github.bitaron.filemanager.api.folder.PatchFolderRequest;
import io.github.bitaron.filemanager.api.paging.CursorPage;
import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.file.FileService;
import io.github.bitaron.filemanager.core.folder.Folder;
import io.github.bitaron.filemanager.core.folder.FolderNotFoundException;
import io.github.bitaron.filemanager.core.folder.FolderService;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The Standalone Service's first REST slice (issue #31): wraps {@link FolderService} over {@code
 * /api/v1/folders}, per ADR 0004's route table. {@link TenantId}/{@link Actor} parameters are
 * resolved by {@code SecureAccessArgumentResolver} from what {@code ApiKeyAuthenticationFilter}
 * already put on the request - no ApiKey/hash handling happens in this class.
 *
 * <p>{@code FolderService} never opens its own transaction (its own javadoc: "the caller owns
 * the transaction boundary") - here, this controller is that caller, so every mutating endpoint
 * is {@code @Transactional}. Read endpoints don't need it: {@code spring.jpa.open-in-view}
 * (Spring Boot's default) already binds a per-request {@code EntityManager} sufficient for reads.
 */
@RestController
@RequestMapping("/api/v1/folders")
@Tag(name = "Folders")
class FolderController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final FolderService folderService;
    private final FileService fileService;

    /**
     * {@code @Lazy} here (not just on {@code FilePersistenceAutoConfiguration.fileService}'s own
     * bean method) is required: a plain constructor-injected {@link FileService} would force
     * Spring to eagerly resolve that dependency the moment this singleton controller is created
     * during context refresh, which defeats the whole point of the producer-side {@code @Lazy} in
     * hosts that never configured a {@code StorageBackend} - this broke
     * {@code FolderControllerIntegrationTest} and {@code FileManagerServiceApplicationTests} (no
     * {@code file-manager.storage.backend} property set, so no {@code StorageBackend} bean) when
     * first tried without it. {@code @Lazy} on this injection point makes Spring hand this
     * controller a lazy proxy instead, deferring real {@link FileService} creation to the first
     * actual File request. Mirrors {@link FileController}'s constructor exactly.
     */
    FolderController(FolderService folderService, @Lazy FileService fileService) {
        this.folderService = folderService;
        this.fileService = fileService;
    }

    @PostMapping
    @Transactional
    @Operation(summary = "Create a Folder", description = "Omit parentFolderId to create a top-level Folder.")
    ResponseEntity<FolderResponse> create(
            @RequestBody CreateFolderRequest request, TenantId tenantId, Actor actor) {
        Folder folder = folderService.create(tenantId, actor, request.name(), request.parentFolderId());
        return ResponseEntity.created(locationOf(folder.getId())).body(FolderMapper.toResponse(folder));
    }

    @GetMapping
    @Operation(
            summary = "List a Folder's immediate children",
            description = "Omit parentId to list top-level Folders. Cursor-paginated (ADR 0004): "
                    + "pass the previous page's nextCursor to fetch the next one.")
    CursorPage<FolderResponse> list(
            @Parameter(description = "Parent Folder id, or omit for top-level Folders")
                    @RequestParam(required = false) UUID parentId,
            @Parameter(description = "Opaque pagination cursor from a previous page's nextCursor")
                    @RequestParam(required = false) UUID cursor,
            @Parameter(description = "Page size, default 50, max 200")
                    @RequestParam(required = false) Integer pageSize,
            TenantId tenantId) {
        if (parentId != null && folderService.fetch(tenantId, parentId) == null) {
            throw new FolderNotFoundException("No Folder with id " + parentId + " exists for this Tenant");
        }

        int limit = clampPageSize(pageSize);
        List<Folder> fetched = folderService.listChildren(tenantId, parentId, cursor, limit + 1);
        return paginate(fetched, limit, Folder::getId, FolderMapper::toResponse);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a single Folder's metadata")
    FolderResponse fetch(@PathVariable UUID id, TenantId tenantId) {
        return FolderMapper.toResponse(fetchOrThrow(tenantId, id));
    }

    @GetMapping("/{id}/files")
    @Operation(
            summary = "List a Folder's immediate Files",
            description = "Cursor-paginated (ADR 0004): pass the previous page's nextCursor to "
                    + "fetch the next one.")
    CursorPage<FileResponse> listFiles(
            @PathVariable UUID id,
            @Parameter(description = "Opaque pagination cursor from a previous page's nextCursor")
                    @RequestParam(required = false) UUID cursor,
            @Parameter(description = "Page size, default 50, max 200")
                    @RequestParam(required = false) Integer pageSize,
            TenantId tenantId) {
        if (folderService.fetch(tenantId, id) == null) {
            throw new FolderNotFoundException("No Folder with id " + id + " exists for this Tenant");
        }

        int limit = clampPageSize(pageSize);
        List<File> fetched = fileService.listFiles(tenantId, id, cursor, limit + 1);
        return paginate(fetched, limit, File::getId, FileMapper::toResponse);
    }

    @PatchMapping("/{id}")
    @Transactional
    @Operation(
            summary = "Rename and/or move a Folder",
            description = "Omit a field to leave it unchanged. A present parentFolderId of null "
                    + "moves the Folder to top-level.")
    FolderResponse patch(
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(schema = @Schema(implementation = PatchFolderRequest.class)))
                    @RequestBody JsonNode body,
            TenantId tenantId,
            Actor actor) {
        Folder folder = null;

        if (body.has("name")) {
            String name = body.get("name").isNull() ? null : body.get("name").asText();
            folder = folderService.rename(tenantId, actor, id, name);
        }
        if (body.has("parentFolderId")) {
            JsonNode parentFolderIdNode = body.get("parentFolderId");
            UUID parentFolderId = parentFolderIdNode.isNull()
                    ? null
                    : UUID.fromString(parentFolderIdNode.asText());
            folder = folderService.move(tenantId, actor, id, parentFolderId);
        }

        return FolderMapper.toResponse(folder != null ? folder : fetchOrThrow(tenantId, id));
    }

    private Folder fetchOrThrow(TenantId tenantId, UUID folderId) {
        Folder folder = folderService.fetch(tenantId, folderId);
        if (folder == null) {
            throw new FolderNotFoundException("No Folder with id " + folderId + " exists for this Tenant");
        }
        return folder;
    }

    /**
     * Turns a "one extra row past {@code limit}" fetch (ADR 0004's cursor-pagination convention)
     * into a {@link CursorPage}: trims the extra row if present and uses it to compute
     * {@code nextCursor}. Shared between {@link #list} and {@link #listFiles} - both paginate the
     * exact same way, just over a different entity/DTO pair.
     */
    private static <T, R> CursorPage<R> paginate(
            List<T> fetchedPlusOne, int limit, Function<T, UUID> idOf, Function<T, R> mapper) {
        boolean hasNextPage = fetchedPlusOne.size() > limit;
        List<T> page = hasNextPage ? fetchedPlusOne.subList(0, limit) : fetchedPlusOne;
        UUID nextCursor = hasNextPage ? idOf.apply(page.get(page.size() - 1)) : null;
        return new CursorPage<>(page.stream().map(mapper).toList(), nextCursor);
    }

    private static int clampPageSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private static URI locationOf(UUID folderId) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/v1/folders/{id}")
                .buildAndExpand(folderId)
                .toUri();
    }
}
