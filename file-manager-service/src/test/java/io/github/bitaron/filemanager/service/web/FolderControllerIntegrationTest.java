package io.github.bitaron.filemanager.service.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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
 * Full-stack REST integration test (docs/testing.md: "exercise the full Standalone Service:
 * REST controller -> core -> ... asserting on HTTP status/body"). Runs against a real embedded
 * server and an embedded H2 database (auto-configured purely from the driver's test-classpath
 * presence, see file-manager-service/pom.xml) - no mocks anywhere in the stack.
 *
 * <p>Establishes the pattern every later REST ticket reuses (issue #31): ApiKey->Tenant
 * resolution before any Folder lookup, cross-tenant {@code 404} (never {@code 403}), cursor
 * pagination, RFC 7807 errors.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "file-manager.storage.backend=local"
        })
@AutoConfigureTestRestTemplate
class FolderControllerIntegrationTest {

    /**
     * Needed for issue #38's purge cascade tests, which upload a real descendant File to prove
     * the Folder purge cascade reaches {@code StorageBackend.delete} end-to-end through the REST
     * layer - mirrors {@link FileControllerIntegrationTest}/{@link TrashControllerIntegrationTest}'s
     * own {@code @TempDir}-backed {@code LocalStorageBackend} setup exactly.
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
    void rejectsARequestWithNoAuthorizationHeader() {
        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/folders", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsARequestWithAMalformedApiKey() {
        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/folders", HttpMethod.GET, new HttpEntity<>(authHeaders("not-an-api-key")), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsARequestWithARevokedApiKey() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        String secret = seedApiKey(tenantId, true);

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/folders", HttpMethod.GET, new HttpEntity<>(authHeaders(secret)), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createsATopLevelFolderAndReturns201WithLocation() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);

        ResponseEntity<FolderResponse> response = restTemplate.exchange(
                "/api/v1/folders",
                HttpMethod.POST,
                new HttpEntity<>(new CreateFolderRequest("Quarterly Reports", null), authHeaders(secret)),
                FolderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        URI location = response.getHeaders().getLocation();
        assertThat(location).isNotNull();
        assertThat(location.getPath()).isEqualTo("/api/v1/folders/" + response.getBody().id());
        assertThat(response.getBody().name()).isEqualTo("Quarterly Reports");
        assertThat(response.getBody().parentFolderId()).isNull();
    }

    @Test
    void fetchesASingleFoldersMetadataById() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse created = create(secret, "Invoices", null);

        ResponseEntity<FolderResponse> response = restTemplate.exchange(
                "/api/v1/folders/" + created.id(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(secret)),
                FolderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(created.id());
    }

    @Test
    void aRequestForAnotherTenantsFolderReturns404NeverA403() {
        String ownerSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        String otherTenantSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = create(ownerSecret, "Private Folder", null);

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/folders/" + folder.id(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(otherTenantSecret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getStatus()).isEqualTo(404);
    }

    @Test
    void aRequestForANonexistentFolderReturns404WithAProblemDetailBody() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/folders/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(secret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void aBlankNameOnCreateReturns400() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/folders",
                HttpMethod.POST,
                new HttpEntity<>(new CreateFolderRequest("  ", null), authHeaders(secret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void malformedJsonBodyReturns400AsAProblemDetailNotSpringBootsDefaultErrorPage() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        HttpHeaders headers = authHeaders(secret);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/folders",
                HttpMethod.POST,
                new HttpEntity<>("not valid json", headers),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void patchRenamesAFolderAndReturns200WithTheUpdatedResource() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse created = create(secret, "Invoices", null);

        ResponseEntity<FolderResponse> response = restTemplate.exchange(
                "/api/v1/folders/" + created.id(),
                HttpMethod.PATCH,
                new HttpEntity<>(java.util.Map.of("name", "Receipts"), authHeaders(secret)),
                FolderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().name()).isEqualTo("Receipts");
    }

    @Test
    void patchWithAnExplicitNullParentFolderIdMovesAFolderToTopLevel() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse parent = create(secret, "Quarterly Reports", null);
        FolderResponse child = create(secret, "2026 Q1", parent.id());

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("parentFolderId", null);
        ResponseEntity<FolderResponse> response = restTemplate.exchange(
                "/api/v1/folders/" + child.id(),
                HttpMethod.PATCH,
                new HttpEntity<>(body, authHeaders(secret)),
                FolderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().parentFolderId()).isNull();
    }

    @Test
    void listingByParentIdBelongingToAnotherTenantReturns404() {
        String ownerSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        String otherTenantSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = create(ownerSecret, "Private Folder", null);

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/folders?parentId=" + folder.id(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(otherTenantSecret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * UUIDv7 only orders by millisecond timestamp - Folders created within the same millisecond
     * have no guaranteed relative order (see file-manager-core's UuidV7), so this derives "the"
     * id order from one unpaginated listing rather than assuming creation order, avoiding flaking
     * on a fast test run.
     */
    @Test
    void listEndpointPaginatesViaAnOpaqueCursor() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        create(secret, "A", null);
        create(secret, "B", null);
        create(secret, "C", null);

        List<FolderResponse> canonicalOrder = list(secret, null, null, 50).items();
        assertThat(canonicalOrder).hasSize(3);

        CursorPage<FolderResponse> firstPage = list(secret, null, null, 2);
        assertThat(firstPage.items()).extracting(FolderResponse::id)
                .containsExactlyElementsOf(canonicalOrder.subList(0, 2).stream().map(FolderResponse::id).toList());
        assertThat(firstPage.nextCursor()).isEqualTo(canonicalOrder.get(1).id());

        CursorPage<FolderResponse> secondPage = list(secret, null, firstPage.nextCursor(), 2);
        assertThat(secondPage.items()).extracting(FolderResponse::id)
                .containsExactly(canonicalOrder.get(2).id());
        assertThat(secondPage.nextCursor()).isNull();
    }

    @Test
    void trashEndpointMarksAFolderTrashedAndRestoreEndpointClearsItAgain() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse created = create(secret, "Invoices", null);
        assertThat(created.trashedAt()).isNull();

        ResponseEntity<FolderResponse> trashResponse = restTemplate.exchange(
                "/api/v1/folders/" + created.id() + "/trash",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(secret)),
                FolderResponse.class);

        assertThat(trashResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(trashResponse.getBody().id()).isEqualTo(created.id());
        assertThat(trashResponse.getBody().trashedAt()).isNotNull();

        ResponseEntity<FolderResponse> restoreResponse = restTemplate.exchange(
                "/api/v1/folders/" + created.id() + "/restore",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(secret)),
                FolderResponse.class);

        assertThat(restoreResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restoreResponse.getBody().trashedAt()).isNull();
    }

    @Test
    void trashingAnotherTenantsFolderReturns404NeverA403() {
        String ownerSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        String otherTenantSecret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = create(ownerSecret, "Private Folder", null);

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/folders/" + folder.id() + "/trash",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(otherTenantSecret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getStatus()).isEqualTo(404);
    }

    @Test
    void purgeEndpointDeletesATrashedFolderAndCascadesToDescendantFolderAndFileAndReturns204() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse root = create(secret, "2026 Archive", null);
        FolderResponse child = create(secret, "Q1", root.id());
        FileResponse file = upload(secret, child.id(), "invoice.txt",
                "invoice content".getBytes(StandardCharsets.UTF_8), "text/plain");
        trashFolder(secret, root.id());

        ResponseEntity<Void> purgeResponse = restTemplate.exchange(
                "/api/v1/folders/" + root.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(secret)),
                Void.class);

        assertThat(purgeResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(fetchFolder(secret, root.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fetchFolder(secret, child.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fetchFile(secret, file.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void purgingAFolderThatIsntTrashedReturns400() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = create(secret, "Invoices", null);

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/folders/" + folder.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(secret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void purgingANonexistentFolderReturns404() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/folders/" + UUID.randomUUID(),
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(secret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private FileResponse upload(String secret, UUID folderId, String name, byte[] content, String contentType) {
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
        body.add("metadata", new HttpEntity<>(new UploadFileMetadata(folderId, name, null), metadataHeaders));

        HttpHeaders headers = authHeaders(secret);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<FileResponse> response = restTemplate.exchange(
                "/api/v1/files", HttpMethod.POST, new HttpEntity<>(body, headers), FileResponse.class);
        return response.getBody();
    }

    private void trashFolder(String secret, UUID folderId) {
        restTemplate.exchange(
                "/api/v1/folders/" + folderId + "/trash",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(secret)),
                FolderResponse.class);
    }

    private ResponseEntity<ProblemDetail> fetchFolder(String secret, UUID folderId) {
        return restTemplate.exchange(
                "/api/v1/folders/" + folderId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(secret)),
                ProblemDetail.class);
    }

    private ResponseEntity<ProblemDetail> fetchFile(String secret, UUID fileId) {
        return restTemplate.exchange(
                "/api/v1/files/" + fileId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(secret)),
                ProblemDetail.class);
    }

    private FolderResponse create(String secret, String name, UUID parentFolderId) {
        ResponseEntity<FolderResponse> response = restTemplate.exchange(
                "/api/v1/folders",
                HttpMethod.POST,
                new HttpEntity<>(new CreateFolderRequest(name, parentFolderId), authHeaders(secret)),
                FolderResponse.class);
        return response.getBody();
    }

    private CursorPage<FolderResponse> list(String secret, UUID parentId, UUID cursor, Integer pageSize) {
        StringBuilder url = new StringBuilder("/api/v1/folders?");
        if (parentId != null) {
            url.append("parentId=").append(parentId).append('&');
        }
        if (cursor != null) {
            url.append("cursor=").append(cursor).append('&');
        }
        if (pageSize != null) {
            url.append("pageSize=").append(pageSize);
        }
        ResponseEntity<CursorPage<FolderResponse>> response = restTemplate.exchange(
                url.toString(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(secret)),
                new ParameterizedTypeReference<>() {
                });
        return response.getBody();
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
