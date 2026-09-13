package io.github.bitaron.filemanager.service.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import io.github.bitaron.filemanager.api.accesstoken.AccessTokenResponse;
import io.github.bitaron.filemanager.api.accesstoken.MintAccessTokenRequest;
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
 * Full-stack REST integration test for issue #36's Non-secure Access slice, mirroring
 * {@link FileControllerIntegrationTest}'s pattern exactly: a real embedded server, an embedded H2
 * database, and a real {@code LocalStorageBackend} rooted at {@link #storageRoot} (a
 * {@code @TempDir}) - so a minted/redeemed AccessToken really round-trips real stored bytes, not
 * just metadata through the database (docs/testing.md's "no mocks anywhere in the stack" rule).
 *
 * <p>Covers docs/testing.md's explicit "a Non-secure Access request with a valid/expired/wrong-
 * Purpose AccessToken" requirement, plus issue #36's acceptance criteria: mint -> redeem round
 * trip with matching bytes/contentType, View vs Download Purpose driving
 * {@code Content-Disposition}, redemption requiring no {@code Authorization} header at all, reuse
 * before expiry, an expired token and a nonexistent token producing byte-identical {@code 404}
 * bodies, minting against a Private File 404ing, Secure Access download of a Public File 404ing
 * (the deferred PR #60 gate), and the mint response shape.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "file-manager.storage.backend=local"
        })
@AutoConfigureTestRestTemplate
class AccessTokenControllerIntegrationTest {

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
    void mintingAndRedeemingAPublicFileRoundTripsByteIdenticalContentAndContentType() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()));
        FolderResponse folder = createFolder(secret, "Public Assets");
        byte[] uploadedBytes = "public logo bytes".getBytes(StandardCharsets.UTF_8);
        FileResponse uploaded = upload(secret, folder.id(), "logo.png", "PUBLIC", uploadedBytes, "image/png");

        AccessTokenResponse minted = mint(secret, uploaded.id(), "VIEW", null);
        ResponseEntity<byte[]> redeemed = redeem(minted.token());

        assertThat(redeemed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(redeemed.getBody()).isEqualTo(uploadedBytes);
        assertThat(redeemed.getHeaders().getContentType().toString()).startsWith("image/png");
    }

    @Test
    void aViewPurposeTokenProducesInlineDispositionAndADownloadPurposeTokenProducesAttachment() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()));
        FolderResponse folder = createFolder(secret, "Public Assets");
        FileResponse uploaded = upload(secret, folder.id(), "report.pdf", "PUBLIC",
                "report bytes".getBytes(StandardCharsets.UTF_8), "application/pdf");

        // Minted independently as their own tokens - not a shared-token distinction (issue #36).
        AccessTokenResponse viewToken = mint(secret, uploaded.id(), "VIEW", null);
        AccessTokenResponse downloadToken = mint(secret, uploaded.id(), "DOWNLOAD", null);

        String viewDisposition = redeem(viewToken.token()).getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        String downloadDisposition =
                redeem(downloadToken.token()).getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);

        assertThat(viewDisposition).startsWith("inline");
        assertThat(viewDisposition).contains("report.pdf");
        assertThat(downloadDisposition).startsWith("attachment");
        assertThat(downloadDisposition).contains("report.pdf");
    }

    @Test
    void redemptionRequiresNoAuthorizationHeaderAtAll() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()));
        FolderResponse folder = createFolder(secret, "Public Assets");
        FileResponse uploaded = upload(secret, folder.id(), "note.txt", "PUBLIC",
                "public note".getBytes(StandardCharsets.UTF_8), "text/plain");
        AccessTokenResponse minted = mint(secret, uploaded.id(), "VIEW", null);

        HttpHeaders headersWithNoAuthorization = new HttpHeaders();
        assertThat(headersWithNoAuthorization.containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/access/" + minted.token(),
                HttpMethod.GET,
                new HttpEntity<>(headersWithNoAuthorization),
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("public note".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aTokenCanBeRedeemedMoreThanOnceBeforeExpiry() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()));
        FolderResponse folder = createFolder(secret, "Public Assets");
        byte[] bytes = "reusable content".getBytes(StandardCharsets.UTF_8);
        FileResponse uploaded = upload(secret, folder.id(), "note.txt", "PUBLIC", bytes, "text/plain");
        AccessTokenResponse minted = mint(secret, uploaded.id(), "DOWNLOAD", null);

        ResponseEntity<byte[]> first = redeem(minted.token());
        ResponseEntity<byte[]> second = redeem(minted.token());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isEqualTo(bytes);
        assertThat(second.getBody()).isEqualTo(bytes);
    }

    @Test
    void anExpiredTokenAndANonexistentTokenReturnByteIdentical404Bodies() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()));
        FolderResponse folder = createFolder(secret, "Public Assets");
        FileResponse uploaded = upload(secret, folder.id(), "note.txt", "PUBLIC",
                "content".getBytes(StandardCharsets.UTF_8), "text/plain");
        // ttlSeconds=0: AccessTokenService.mint doesn't reject a zero/negative TTL, and
        // expiresAt == mintedAt means the very next Instant.now() check in redeem() finds it
        // already expired - a clean, deterministic way to mint an already-expired token without
        // sleeping past a real TTL.
        AccessTokenResponse expiredToken = mint(secret, uploaded.id(), "VIEW", 0L);

        ResponseEntity<String> expiredResponse = redeemRaw(expiredToken.token());
        ResponseEntity<String> nonexistentResponse = redeemRaw("this-token-was-never-minted");

        assertThat(expiredResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(nonexistentResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Not just "both are 404" - the bodies themselves must be indistinguishable (ADR 0004's
        // non-enumerability rule): a guessed/expired token must never be able to tell itself apart
        // from one that never existed.
        assertThat(expiredResponse.getBody()).isEqualTo(nonexistentResponse.getBody());
    }

    @Test
    void mintingAgainstAPrivateFileReturns404() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()));
        FolderResponse folder = createFolder(secret, "Private Assets");
        FileResponse uploaded = upload(secret, folder.id(), "secret.txt", "PRIVATE",
                "secret content".getBytes(StandardCharsets.UTF_8), "text/plain");

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/files/" + uploaded.id() + "/access-tokens",
                HttpMethod.POST,
                new HttpEntity<>(new MintAccessTokenRequest("VIEW", null), authHeaders(secret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getStatus()).isEqualTo(404);
    }

    @Test
    void secureAccessDownloadOfAPublicFileReturns404() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()));
        FolderResponse folder = createFolder(secret, "Public Assets");
        FileResponse uploaded = upload(secret, folder.id(), "logo.png", "PUBLIC",
                "public bytes".getBytes(StandardCharsets.UTF_8), "image/png");

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/api/v1/files/" + uploaded.id() + "/content",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(secret)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getStatus()).isEqualTo(404);
    }

    @Test
    void mintResponseBodyHasTokenExpiresAtAndTheConstructedRedemptionUrl() {
        String secret = seedApiKey(new TenantId(UUID.randomUUID()));
        FolderResponse folder = createFolder(secret, "Public Assets");
        FileResponse uploaded = upload(secret, folder.id(), "logo.png", "PUBLIC",
                "public bytes".getBytes(StandardCharsets.UTF_8), "image/png");

        ResponseEntity<AccessTokenResponse> response = restTemplate.exchange(
                "/api/v1/files/" + uploaded.id() + "/access-tokens",
                HttpMethod.POST,
                new HttpEntity<>(new MintAccessTokenRequest("VIEW", null), authHeaders(secret)),
                AccessTokenResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AccessTokenResponse body = response.getBody();
        assertThat(body.token()).isNotBlank();
        assertThat(body.expiresAt()).isNotNull();
        assertThat(body.url()).isEqualTo("/access/" + body.token());
    }

    private AccessTokenResponse mint(String secret, UUID fileId, String purpose, Long ttlSeconds) {
        ResponseEntity<AccessTokenResponse> response = restTemplate.exchange(
                "/api/v1/files/" + fileId + "/access-tokens",
                HttpMethod.POST,
                new HttpEntity<>(new MintAccessTokenRequest(purpose, ttlSeconds), authHeaders(secret)),
                AccessTokenResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<byte[]> redeem(String token) {
        return restTemplate.exchange("/access/" + token, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
    }

    private ResponseEntity<String> redeemRaw(String token) {
        return restTemplate.exchange("/access/" + token, HttpMethod.GET, HttpEntity.EMPTY, String.class);
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

    /**
     * Builds the {@code file} + {@code metadata} multipart/form-data request body per ADR 0004,
     * mirroring {@link FileControllerIntegrationTest#multipartRequest} exactly.
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

    private String seedApiKey(TenantId tenantId) {
        String secret = ApiKeySecret.generate().value();
        ApiKey apiKey = ApiKey.issue(tenantId, "test", ApiKeyHasher.hash(secret));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> entityManager.persist(apiKey));
        return secret;
    }
}
