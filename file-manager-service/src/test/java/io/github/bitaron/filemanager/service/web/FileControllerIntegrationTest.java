package io.github.bitaron.filemanager.service.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.bitaron.filemanager.api.file.FileResponse;
import io.github.bitaron.filemanager.api.file.UploadFileMetadata;
import io.github.bitaron.filemanager.api.folder.CreateFolderRequest;
import io.github.bitaron.filemanager.api.folder.FolderResponse;
import io.github.bitaron.filemanager.api.paging.CursorPage;
import io.github.bitaron.filemanager.core.apikey.ApiKey;
import io.github.bitaron.filemanager.core.apikey.ApiKeyHasher;
import io.github.bitaron.filemanager.core.apikey.ApiKeySecret;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack REST integration test for issue #33 (File upload/download round-trip), mirroring
 * {@link FolderControllerIntegrationTest}'s pattern exactly: a real embedded server, an embedded
 * H2 database, and here also a real {@code LocalStorageBackend} rooted at {@link #storageRoot} (a
 * {@code @TempDir}) - so uploaded content really round-trips through disk, not just metadata
 * through the database (docs/testing.md's "no mocks anywhere in the stack" rule).
 *
 * <p>Covers every acceptance criterion from AGENT-BRIEF.md/issue #33: upload into a specific
 * Folder with byte-for-byte + contentType round-trip, omitted {@code Visibility} defaulting to
 * Private, {@code disposition=view} vs {@code download} producing different
 * {@code Content-Disposition} values, an ApiKey failure mode returning {@code 401} (Folder's
 * existing test already exhaustively covers missing/malformed/revoked - this only needs to prove
 * File sits behind the same filter), cross-tenant access returning {@code 404} never {@code 403},
 * and the raw upload response body never containing the string {@code storageReference}.
 *
 * <p>Also covers issue #34's Standalone Service REST slice: {@code GET /folders/{id}/files}
 * (cursor-paginated, mirroring {@link FolderControllerIntegrationTest}'s equivalent list test),
 * {@code GET /files/{id}}, and {@code PATCH /files/{id}} (rename, move, and the File-specific rule
 * that an explicit {@code null} {@code parentFolderId} is rejected with {@code 400} rather than
 * moving the File to top-level, unlike Folder's own {@code PATCH}).
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "file-manager.storage.backend=local"
        })
@AutoConfigureTestRestTemplate
class FileControllerIntegrationTest {

    /**
     * Static so {@link #storageProperties} (also static, per {@code @DynamicPropertySource}'s
     * contract) can read it while the {@code ApplicationContext} is still being prepared - JUnit
     * populates static {@code @TempDir} fields before Spring's dynamic property resolution runs.
     * Mirrors {@code FilePersistenceIntegrationTest} in file-manager-spring-boot-starter.
     */
    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("file-manager.storage.local.root-directory", () -> storageRoot.toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void uploadsAndDownloadsAFileRoundTripWithMatchingBytesAndContentType() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(secret, "Invoices");
        byte[] uploadedBytes = "Q1 invoice content".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<FileResponse> uploadResponse = restTemplate.exchange(
                "/api/v1/files",
                HttpMethod.POST,
                multipartRequest(secret, folder.id(), "invoice.txt", "PRIVATE", uploadedBytes, "text/plain"),
                FileResponse.class);

        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        URI location = uploadResponse.getHeaders().getLocation();
        assertThat(location).isNotNull();
        FileResponse uploaded = uploadResponse.getBody();
        assertThat(location.getPath()).isEqualTo("/api/v1/files/" + uploaded.id());
        assertThat(uploaded.name()).isEqualTo("invoice.txt");
        assertThat(uploaded.parentFolderId()).isEqualTo(folder.id());
        assertThat(uploaded.contentType()).isEqualTo("text/plain");
        assertThat(uploaded.size()).isEqualTo(uploadedBytes.length);
        assertThat(uploaded.visibility()).isEqualTo("PRIVATE");

        ResponseEntity<byte[]> downloadResponse = download(secret, uploaded.id(), null);

        assertThat(downloadResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadResponse.getBody()).isEqualTo(uploadedBytes);
        assertThat(downloadResponse.getHeaders().getContentType().toString()).startsWith("text/plain");
    }

    @Test
    void omittingVisibilityOnUploadDefaultsToPrivate() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(secret, "Invoices");
        byte[] bytes = "note content".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<FileResponse> response = restTemplate.exchange(
                "/api/v1/files",
                HttpMethod.POST,
                multipartRequest(secret, folder.id(), "note.txt", null, bytes, "text/plain"),
                FileResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().visibility()).isEqualTo("PRIVATE");
    }

    @Test
    void dispositionQueryParamControlsTheContentDispositionHeaderAndDefaultsToDownload() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(secret, "Invoices");
        FileResponse uploaded = upload(secret, folder.id(), "note.txt", null,
                "note content".getBytes(StandardCharsets.UTF_8), "text/plain");

        String viewDisposition = download(secret, uploaded.id(), "view")
                .getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        String downloadDisposition = download(secret, uploaded.id(), "download")
                .getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        String defaultDisposition = download(secret, uploaded.id(), null)
                .getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);

        assertThat(viewDisposition).startsWith("inline");
        assertThat(viewDisposition).contains("note.txt");
        assertThat(downloadDisposition).startsWith("attachment");
        assertThat(downloadDisposition).contains("note.txt");
        assertThat(defaultDisposition).isEqualTo(downloadDisposition);
    }

    @Test
    void downloadingWithNoAuthorizationHeaderReturns401() {
        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/files/" + UUID.randomUUID() + "/content",
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aRequestForAnotherTenantsFileReturns404NeverA403() {
        String ownerSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        String otherTenantSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(ownerSecret, "Private Folder");
        FileResponse uploaded = upload(ownerSecret, folder.id(), "secret.txt", null,
                "secret content".getBytes(StandardCharsets.UTF_8), "text/plain");

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/files/" + uploaded.id() + "/content",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(otherTenantSecret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getStatus()).isEqualTo(404);
    }

    @Test
    void theUploadResponseBodyNeverIncludesStorageReference() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(secret, "Invoices");
        byte[] bytes = "note content".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/files",
                HttpMethod.POST,
                multipartRequest(secret, folder.id(), "note.txt", null, bytes, "text/plain"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).doesNotContain("storageReference");
    }

    /**
     * UUIDv7 only orders by millisecond timestamp - Files created within the same millisecond
     * have no guaranteed relative order (see file-manager-core's UuidV7), so this derives "the"
     * id order from one unpaginated listing rather than assuming creation order, avoiding flaking
     * on a fast test run. Mirrors {@link FolderControllerIntegrationTest#listEndpointPaginatesViaAnOpaqueCursor}.
     */
    @Test
    void listFilesEndpointPaginatesViaAnOpaqueCursor() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(secret, "Invoices");
        upload(secret, folder.id(), "a.txt", null, "a".getBytes(StandardCharsets.UTF_8), "text/plain");
        upload(secret, folder.id(), "b.txt", null, "b".getBytes(StandardCharsets.UTF_8), "text/plain");
        upload(secret, folder.id(), "c.txt", null, "c".getBytes(StandardCharsets.UTF_8), "text/plain");

        List<FileResponse> canonicalOrder = listFiles(secret, folder.id(), null, 50).items();
        assertThat(canonicalOrder).hasSize(3);

        CursorPage<FileResponse> firstPage = listFiles(secret, folder.id(), null, 2);
        assertThat(firstPage.items()).extracting(FileResponse::id)
                .containsExactlyElementsOf(canonicalOrder.subList(0, 2).stream().map(FileResponse::id).toList());
        assertThat(firstPage.nextCursor()).isEqualTo(canonicalOrder.get(1).id());

        CursorPage<FileResponse> secondPage = listFiles(secret, folder.id(), firstPage.nextCursor(), 2);
        assertThat(secondPage.items()).extracting(FileResponse::id)
                .containsExactly(canonicalOrder.get(2).id());
        assertThat(secondPage.nextCursor()).isNull();
    }

    @Test
    void listingFilesForANonexistentFolderReturns404() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/folders/" + UUID.randomUUID() + "/files",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(secret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listingFilesForAnotherTenantsFolderReturns404NeverA403() {
        String ownerSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        String otherTenantSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(ownerSecret, "Private Folder");

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/folders/" + folder.id() + "/files",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(otherTenantSecret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listFilesResponseBodyNeverIncludesStorageReference() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(secret, "Invoices");
        upload(secret, folder.id(), "note.txt", null,
                "note content".getBytes(StandardCharsets.UTF_8), "text/plain");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/folders/" + folder.id() + "/files",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(secret)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("storageReference");
    }

    @Test
    void fetchesASingleFilesMetadataById() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(secret, "Invoices");
        FileResponse uploaded = upload(secret, folder.id(), "invoice.txt", null,
                "invoice content".getBytes(StandardCharsets.UTF_8), "text/plain");

        ResponseEntity<FileResponse> response = restTemplate.exchange(
                "/api/v1/files/" + uploaded.id(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(secret)),
                FileResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(uploaded.id());
        assertThat(response.getBody().name()).isEqualTo("invoice.txt");
    }

    @Test
    void fetchMetadataResponseBodyNeverIncludesStorageReference() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(secret, "Invoices");
        FileResponse uploaded = upload(secret, folder.id(), "invoice.txt", null,
                "invoice content".getBytes(StandardCharsets.UTF_8), "text/plain");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/files/" + uploaded.id(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(secret)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("storageReference");
    }

    @Test
    void aRequestForAnotherTenantsFileMetadataReturns404NeverA403() {
        String ownerSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        String otherTenantSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(ownerSecret, "Private Folder");
        FileResponse uploaded = upload(ownerSecret, folder.id(), "secret.txt", null,
                "secret content".getBytes(StandardCharsets.UTF_8), "text/plain");

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/files/" + uploaded.id(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(otherTenantSecret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getStatus()).isEqualTo(404);
    }

    @Test
    void aRequestForANonexistentFileMetadataReturns404() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/files/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(secret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void patchRenamesAFileAndReturns200WithTheUpdatedResource() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(secret, "Invoices");
        FileResponse uploaded = upload(secret, folder.id(), "invoice.txt", null,
                "invoice content".getBytes(StandardCharsets.UTF_8), "text/plain");

        ResponseEntity<FileResponse> response = patch(secret, uploaded.id(), Map.of("name", "receipt.txt"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().name()).isEqualTo("receipt.txt");
        assertThat(response.getBody().parentFolderId()).isEqualTo(folder.id());
    }

    @Test
    void patchMovesAFileToADifferentFolder() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse oldFolder = createFolder(secret, "2026 Q1");
        FolderResponse newFolder = createFolder(secret, "2026 Q2");
        FileResponse uploaded = upload(secret, oldFolder.id(), "invoice.txt", null,
                "invoice content".getBytes(StandardCharsets.UTF_8), "text/plain");

        ResponseEntity<FileResponse> response = patch(
                secret, uploaded.id(), Map.of("parentFolderId", newFolder.id().toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().parentFolderId()).isEqualTo(newFolder.id());
    }

    @Test
    void patchWithAnExplicitNullParentFolderIdReturns400BecauseAFileCanNeverBeTopLevel() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(secret, "Invoices");
        FileResponse uploaded = upload(secret, folder.id(), "invoice.txt", null,
                "invoice content".getBytes(StandardCharsets.UTF_8), "text/plain");

        Map<String, Object> body = new HashMap<>();
        body.put("parentFolderId", null);
        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/files/" + uploaded.id(),
                HttpMethod.PATCH,
                new HttpEntity<>(body, authHeaders(secret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void trashEndpointMarksAFileTrashedAndRestoreEndpointClearsItAgain() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(secret, "Invoices");
        FileResponse uploaded = upload(secret, folder.id(), "invoice.txt", null,
                "invoice content".getBytes(StandardCharsets.UTF_8), "text/plain");
        assertThat(uploaded.trashedAt()).isNull();

        ResponseEntity<FileResponse> trashResponse = restTemplate.exchange(
                "/api/v1/files/" + uploaded.id() + "/trash",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(secret)),
                FileResponse.class);

        assertThat(trashResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(trashResponse.getBody().id()).isEqualTo(uploaded.id());
        assertThat(trashResponse.getBody().trashedAt()).isNotNull();

        ResponseEntity<FileResponse> restoreResponse = restTemplate.exchange(
                "/api/v1/files/" + uploaded.id() + "/restore",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(secret)),
                FileResponse.class);

        assertThat(restoreResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restoreResponse.getBody().trashedAt()).isNull();
    }

    @Test
    void trashingAnotherTenantsFileReturns404NeverA403() {
        String ownerSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        String otherTenantSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(ownerSecret, "Private Folder");
        FileResponse uploaded = upload(ownerSecret, folder.id(), "secret.txt", null,
                "secret content".getBytes(StandardCharsets.UTF_8), "text/plain");

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/files/" + uploaded.id() + "/trash",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(otherTenantSecret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getStatus()).isEqualTo(404);
    }

    private FolderResponse createFolder(String secret, String name) {
        ResponseEntity<FolderResponse> response = restTemplate.exchange(
                "/api/v1/folders",
                HttpMethod.POST,
                new HttpEntity<>(new CreateFolderRequest(name, null), authHeaders(secret)),
                FolderResponse.class);
        return response.getBody();
    }

    private FileResponse upload(String secret, UUID folderId, String name, String visibility,
            byte[] content, String contentType) {
        ResponseEntity<FileResponse> response = restTemplate.exchange(
                "/api/v1/files",
                HttpMethod.POST,
                multipartRequest(secret, folderId, name, visibility, content, contentType),
                FileResponse.class);
        return response.getBody();
    }

    private ResponseEntity<byte[]> download(String secret, UUID fileId, String disposition) {
        String url = "/api/v1/files/" + fileId + "/content"
                + (disposition != null ? "?disposition=" + disposition : "");
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders(secret)), byte[].class);
    }

    private CursorPage<FileResponse> listFiles(String secret, UUID folderId, UUID cursor, Integer pageSize) {
        StringBuilder url = new StringBuilder("/api/v1/folders/").append(folderId).append("/files?");
        if (cursor != null) {
            url.append("cursor=").append(cursor).append('&');
        }
        if (pageSize != null) {
            url.append("pageSize=").append(pageSize);
        }
        ResponseEntity<CursorPage<FileResponse>> response = restTemplate.exchange(
                url.toString(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(secret)),
                new ParameterizedTypeReference<>() {
                });
        return response.getBody();
    }

    private ResponseEntity<FileResponse> patch(String secret, UUID fileId, Map<String, Object> body) {
        return restTemplate.exchange(
                "/api/v1/files/" + fileId,
                HttpMethod.PATCH,
                new HttpEntity<>(body, authHeaders(secret)),
                FileResponse.class);
    }

    /**
     * Builds the {@code file} + {@code metadata} multipart/form-data request body per ADR 0004:
     * a binary {@code file} part (as a named {@link ByteArrayResource}) alongside a JSON
     * {@code metadata} part bound to {@link UploadFileMetadata}.
     */
    private HttpEntity<MultiValueMap<String, Object>> multipartRequest(String secret, UUID folderId,
            String name, String visibility, byte[] content, String contentType) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        HttpHeaders filePartHeaders = new HttpHeaders();
        filePartHeaders.setContentType(MediaType.parseMediaType(contentType));
        ByteArrayResource fileResource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return name;
            }
        };
        body.add("file", new HttpEntity<>(fileResource, filePartHeaders));

        HttpHeaders metadataHeaders = new HttpHeaders();
        metadataHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("metadata", new HttpEntity<>(new UploadFileMetadata(folderId, name, visibility), metadataHeaders));

        HttpHeaders headers = authHeaders(secret);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    private static HttpHeaders authHeaders(String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secret);
        return headers;
    }

    private String seedApiKey(TenantId tenantId, boolean revoked) {
        String secret = ApiKeySecret.generate().value();
        ApiKey apiKey = ApiKey.issue(tenantId, "test", ApiKeyHasher.hash(secret));
        if (revoked) {
            apiKey.revoke();
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> entityManager.persist(apiKey));
        return secret;
    }
}
