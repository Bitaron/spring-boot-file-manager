package io.github.bitaron.filemanager.service.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import io.github.bitaron.filemanager.api.file.FileResponse;
import io.github.bitaron.filemanager.api.file.UploadFileMetadata;
import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.file.FileContent;
import io.github.bitaron.filemanager.core.file.FileService;
import io.github.bitaron.filemanager.core.file.Visibility;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The Standalone Service's File slice (issue #33): wraps {@link FileService} over {@code
 * /api/v1/files}, per ADR 0004's route table. Mirrors {@link FolderController}'s shape/Javadoc
 * conventions exactly. {@link TenantId}/{@link Actor} parameters are resolved by {@code
 * SecureAccessArgumentResolver} from what {@code ApiKeyAuthenticationFilter} already put on the
 * request - no ApiKey/hash handling happens in this class.
 *
 * <p>{@code FileService} never opens its own transaction (its own javadoc mirrors {@code
 * FolderService}'s "the caller owns the transaction boundary") - here, this controller is that
 * caller, so {@link #upload} is {@code @Transactional}. {@link #download} isn't: it's a read path,
 * and {@code spring.jpa.open-in-view} (Spring Boot's default) already binds a per-request {@code
 * EntityManager} sufficient for it.
 */
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "Files")
class FileController {

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
     * actual File request.
     */
    FileController(@Lazy FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    @Operation(
            summary = "Upload a File",
            description = "multipart/form-data: a binary 'file' part plus a JSON 'metadata' part "
                    + "(UploadFileMetadata). Omitting metadata.visibility defaults to Private.")
    ResponseEntity<FileResponse> upload(
            @RequestPart("file") MultipartFile multipartFile,
            @RequestPart("metadata") UploadFileMetadata request,
            TenantId tenantId,
            Actor actor) {
        Visibility visibility = parseVisibility(request.visibility());
        File file;
        try (InputStream content = multipartFile.getInputStream()) {
            file = fileService.upload(
                    tenantId,
                    actor,
                    request.folderId(),
                    request.name(),
                    visibility,
                    content,
                    multipartFile.getSize(),
                    multipartFile.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ResponseEntity.created(locationOf(file.getId())).body(FileMapper.toResponse(file));
    }

    @GetMapping("/{id}/content")
    @Operation(
            summary = "Download a File's content",
            description = "disposition=view renders inline (Content-Disposition: inline); "
                    + "disposition=download (the default) forces a browser download "
                    + "(Content-Disposition: attachment).")
    ResponseEntity<InputStreamResource> download(
            @PathVariable UUID id,
            @Parameter(description = "'view' or 'download' (default 'download')")
                    @RequestParam(required = false) String disposition,
            TenantId tenantId) {
        FileContent fileContent = fileService.fetchContent(tenantId, id);
        File file = fileContent.file();

        boolean inline = "view".equalsIgnoreCase(disposition);
        // RFC 5987 (filename*=UTF-8''...) encoding via the (String, Charset) overload - not the
        // plain filename(String) one, which leaves the builder's charset null and writes non-ASCII
        // names into the header as raw, un-encoded bytes (headers are effectively Latin-1).
        ContentDisposition contentDisposition = (inline
                        ? ContentDisposition.inline()
                        : ContentDisposition.attachment())
                .filename(file.getName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(new InputStreamResource(fileContent.content()));
    }

    private static Visibility parseVisibility(String visibility) {
        return visibility == null || visibility.isBlank() ? null : Visibility.valueOf(visibility);
    }

    private static URI locationOf(UUID fileId) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/v1/files/{id}")
                .buildAndExpand(fileId)
                .toUri();
    }
}
