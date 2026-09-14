package io.github.bitaron.filemanager.service.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.bitaron.filemanager.api.file.FileResponse;
import io.github.bitaron.filemanager.api.file.UploadFileMetadata;
import io.github.bitaron.filemanager.api.folder.CreateFolderRequest;
import io.github.bitaron.filemanager.api.folder.FolderResponse;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack REST integration test for issue #37's unified trash listing ({@code
 * GET /api/v1/trash}), mirroring {@link FolderControllerIntegrationTest}/
 * {@link FileControllerIntegrationTest}'s pattern exactly: a real embedded server, an embedded H2
 * database, and a real {@code LocalStorageBackend} rooted at {@link #storageRoot} so an uploaded
 * File can actually be trashed through the real REST round-trip, not just seeded directly.
 *
 * <p>Deserializes each trash-bin row onto a private, test-local {@link TrashItemDto} rather than
 * a shared {@code file-manager-api} DTO: {@code TrashItemResponse} doesn't exist yet (issue #37's
 * production-code scope), so this only asserts the wire shape the ticket's brief specifies
 * ({@code type}, {@code id}, {@code name}, {@code parentFolderId}, {@code trashedAt}), not any
 * particular class.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "file-manager.storage.backend=local"
        })
@AutoConfigureTestRestTemplate
class TrashControllerIntegrationTest {

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
    void trashListingWithNoParentIdReturnsBothATrashedFolderAndATrashedFileDiscriminatedByType() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse folder = createFolder(secret, "Invoices");
        FolderResponse toTrash = createFolder(secret, "Old Reports");
        FileResponse uploaded = upload(secret, folder.id(), "invoice.txt",
                "invoice content".getBytes(StandardCharsets.UTF_8), "text/plain");
        trashFolder(secret, toTrash.id());
        trashFile(secret, uploaded.id());

        List<TrashItemDto> items = listTrash(secret, null);

        assertThat(items).hasSize(2);
        assertThat(items).extracting(TrashItemDto::type).containsExactlyInAnyOrder("FOLDER", "FILE");
        assertThat(items).extracting(TrashItemDto::id).containsExactlyInAnyOrder(toTrash.id(), uploaded.id());
        assertThat(items).allSatisfy(item -> assertThat(item.trashedAt()).isNotNull());
    }

    @Test
    void trashListingScopedByParentIdOnlyReturnsThatFoldersDirectTrashedChildren() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()), false);
        FolderResponse scopedParent = createFolder(secret, "Scoped Parent");
        FolderResponse otherParent = createFolder(secret, "Other Parent");
        FileResponse inScope = upload(secret, scopedParent.id(), "in-scope.txt",
                "in scope".getBytes(StandardCharsets.UTF_8), "text/plain");
        FileResponse outOfScope = upload(secret, otherParent.id(), "out-of-scope.txt",
                "out of scope".getBytes(StandardCharsets.UTF_8), "text/plain");
        trashFile(secret, inScope.id());
        trashFile(secret, outOfScope.id());

        List<TrashItemDto> items = listTrash(secret, scopedParent.id());

        assertThat(items).extracting(TrashItemDto::id).containsExactly(inScope.id());
    }

    private FolderResponse createFolder(String secret, String name) {
        ResponseEntity<FolderResponse> response = restTemplate.exchange(
                "/api/v1/folders",
                HttpMethod.POST,
                new HttpEntity<>(new CreateFolderRequest(name, null), authHeaders(secret)),
                FolderResponse.class);
        return response.getBody();
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

    private void trashFile(String secret, UUID fileId) {
        restTemplate.exchange(
                "/api/v1/files/" + fileId + "/trash",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(secret)),
                FileResponse.class);
    }

    private List<TrashItemDto> listTrash(String secret, UUID parentId) {
        String url = "/api/v1/trash" + (parentId != null ? "?parentId=" + parentId : "");
        ResponseEntity<List<TrashItemDto>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(secret)),
                new ParameterizedTypeReference<>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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

    /**
     * Test-local mirror of the wire shape the brief specifies for {@code TrashItemResponse}
     * ({@code type}/{@code id}/{@code name}/{@code parentFolderId}/{@code trashedAt}) - not the
     * production DTO itself, which doesn't exist yet.
     */
    private record TrashItemDto(String type, UUID id, String name, UUID parentFolderId, Instant trashedAt) {
    }
}
