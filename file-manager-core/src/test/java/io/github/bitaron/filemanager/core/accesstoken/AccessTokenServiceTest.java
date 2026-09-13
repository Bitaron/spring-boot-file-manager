package io.github.bitaron.filemanager.core.accesstoken;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.file.FileNotFoundException;
import io.github.bitaron.filemanager.core.file.FileService;
import io.github.bitaron.filemanager.core.file.Visibility;
import io.github.bitaron.filemanager.core.folder.Folder;
import io.github.bitaron.filemanager.core.folder.FolderService;
import io.github.bitaron.filemanager.core.jpa.TestEntityManagerFactory;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import io.github.bitaron.filemanager.storage.StorageBackend;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AccessTokenService}: no Spring context. {@link AccessTokenLookup} is faked
 * out with an in-memory map (docs/testing.md), mirroring {@code core.file.FileServiceTest}'s
 * pattern for {@code FileLookup}. {@link FileService} itself is backed by a real,
 * in-memory-H2 {@link EntityManager} (via its public constructor) plus a tiny in-memory
 * {@link StorageBackend} fake, rather than a fake {@code FileLookup} - that seam is
 * package-private to {@code core.file} and unreachable from this package, the same reason
 * {@code FileServiceTest} itself backs {@code FolderService} with a real {@code EntityManager}
 * instead of a fake {@code FolderLookup}.
 */
class AccessTokenServiceTest {

    private static final long DEFAULT_TTL_CEILING_SECONDS = 86_400L;

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private FolderService folderService;
    private InMemoryStorageBackend storageBackend;
    private FileService fileService;

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
        storageBackend = new InMemoryStorageBackend();
        fileService = new FileService(entityManager, folderService, storageBackend);
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    void mintCreatesAccessTokenForPublicFileWithDefaultTtlWhenNoTtlRequested() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File file = uploadFile(tenantId, actor, Visibility.PUBLIC, "hello world".getBytes(StandardCharsets.UTF_8));

        FakeAccessTokenLookup accessTokenLookup = new FakeAccessTokenLookup();
        AccessTokenService accessTokenService = new AccessTokenService(
                accessTokenLookup, fileService, DEFAULT_TTL_CEILING_SECONDS, DEFAULT_TTL_CEILING_SECONDS);

        AccessToken minted = accessTokenService.mint(tenantId, actor, file.getId(), Purpose.VIEW, null);

        assertThat(minted.getToken()).isNotBlank();
        assertThat(minted.getFileId()).isEqualTo(file.getId());
        assertThat(minted.getPurpose()).isEqualTo(Purpose.VIEW);
        assertThat(minted.tenantId()).isEqualTo(tenantId);
        assertThat(minted.mintedBy()).isEqualTo(actor);
        // Default TTL is 900s (15 minutes) when no TTL is requested (decision #14).
        assertThat(Duration.between(minted.getMintedAt(), minted.getExpiresAt()).getSeconds()).isEqualTo(900L);
        assertThat(accessTokenLookup.saved).isSameAs(minted);
    }

    @Test
    void mintCapsRequestedTtlAtThePerPurposeCeiling() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File file = uploadFile(tenantId, actor, Visibility.PUBLIC, "hello world".getBytes(StandardCharsets.UTF_8));

        FakeAccessTokenLookup accessTokenLookup = new FakeAccessTokenLookup();
        // View ceiling of 100s; a requested TTL far above it must be capped down to 100.
        AccessTokenService accessTokenService = new AccessTokenService(accessTokenLookup, fileService, 100L, 99_999L);

        AccessToken minted = accessTokenService.mint(tenantId, actor, file.getId(), Purpose.VIEW, 100_000L);

        assertThat(Duration.between(minted.getMintedAt(), minted.getExpiresAt()).getSeconds()).isEqualTo(100L);
    }

    @Test
    void mintUsesRequestedTtlWhenBelowTheCeiling() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File file = uploadFile(tenantId, actor, Visibility.PUBLIC, "hello world".getBytes(StandardCharsets.UTF_8));

        FakeAccessTokenLookup accessTokenLookup = new FakeAccessTokenLookup();
        AccessTokenService accessTokenService = new AccessTokenService(accessTokenLookup, fileService, 1_000L, 1_000L);

        AccessToken minted = accessTokenService.mint(tenantId, actor, file.getId(), Purpose.DOWNLOAD, 50L);

        assertThat(Duration.between(minted.getMintedAt(), minted.getExpiresAt()).getSeconds()).isEqualTo(50L);
    }

    @Test
    void mintThrowsIllegalArgumentExceptionForANegativeRequestedTtl() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File file = uploadFile(tenantId, actor, Visibility.PUBLIC, "hello world".getBytes(StandardCharsets.UTF_8));

        FakeAccessTokenLookup accessTokenLookup = new FakeAccessTokenLookup();
        AccessTokenService accessTokenService = new AccessTokenService(
                accessTokenLookup, fileService, DEFAULT_TTL_CEILING_SECONDS, DEFAULT_TTL_CEILING_SECONDS);

        // A negative TTL is never meaningful and would otherwise silently mint an already-expired
        // token with no error - unlike zero, which is a deliberately allowed edge case (an
        // immediately-expired token is still a well-formed request, and the one deterministic way
        // callers have to produce one for testing).
        assertThatThrownBy(() -> accessTokenService.mint(tenantId, actor, file.getId(), Purpose.VIEW, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(AccessTokenNotFoundException.class);
    }

    @Test
    void mintThrowsAccessTokenNotFoundExceptionWhenFileIsPrivate() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        File file = uploadFile(tenantId, actor, Visibility.PRIVATE, "hello world".getBytes(StandardCharsets.UTF_8));

        FakeAccessTokenLookup accessTokenLookup = new FakeAccessTokenLookup();
        AccessTokenService accessTokenService = new AccessTokenService(
                accessTokenLookup, fileService, DEFAULT_TTL_CEILING_SECONDS, DEFAULT_TTL_CEILING_SECONDS);

        assertThatThrownBy(() -> accessTokenService.mint(tenantId, actor, file.getId(), Purpose.VIEW, null))
                .isInstanceOf(AccessTokenNotFoundException.class);
    }

    @Test
    void mintThrowsFileNotFoundExceptionWhenFileDoesNotResolveForTenant() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        UUID fileId = UUID.randomUUID();

        FakeAccessTokenLookup accessTokenLookup = new FakeAccessTokenLookup();
        AccessTokenService accessTokenService = new AccessTokenService(
                accessTokenLookup, fileService, DEFAULT_TTL_CEILING_SECONDS, DEFAULT_TTL_CEILING_SECONDS);

        assertThatThrownBy(() -> accessTokenService.mint(tenantId, actor, fileId, Purpose.VIEW, null))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void redeemReturnsFileContentAndPurposeForAValidToken() throws IOException {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
        File file = uploadFile(tenantId, actor, Visibility.PUBLIC, bytes);

        FakeAccessTokenLookup accessTokenLookup = new FakeAccessTokenLookup();
        AccessTokenService accessTokenService = new AccessTokenService(
                accessTokenLookup, fileService, DEFAULT_TTL_CEILING_SECONDS, DEFAULT_TTL_CEILING_SECONDS);
        AccessToken minted = accessTokenService.mint(tenantId, actor, file.getId(), Purpose.DOWNLOAD, null);

        AccessTokenRedemption redemption = accessTokenService.redeem(minted.getToken());

        assertThat(redemption.purpose()).isEqualTo(Purpose.DOWNLOAD);
        assertThat(redemption.fileContent().file().getId()).isEqualTo(file.getId());
        assertThat(redemption.fileContent().content().readAllBytes()).isEqualTo(bytes);
    }

    @Test
    void redeemThrowsAccessTokenNotFoundExceptionForANonexistentToken() {
        FakeAccessTokenLookup accessTokenLookup = new FakeAccessTokenLookup();
        AccessTokenService accessTokenService = new AccessTokenService(
                accessTokenLookup, fileService, DEFAULT_TTL_CEILING_SECONDS, DEFAULT_TTL_CEILING_SECONDS);

        assertThatThrownBy(() -> accessTokenService.redeem("does-not-exist"))
                .isInstanceOf(AccessTokenNotFoundException.class);
    }

    @Test
    void redeemThrowsAccessTokenNotFoundExceptionForAnExpiredToken() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        Instant now = Instant.now();
        // Constructed directly (this test lives in the same package) rather than via mint(),
        // since mint() only ever produces tokens with a future expiresAt.
        AccessToken expired = new AccessToken("expired-token-value", tenantId, UUID.randomUUID(), Purpose.VIEW,
                now.minusSeconds(60), actor, now.minusSeconds(3600));

        FakeAccessTokenLookup accessTokenLookup = new FakeAccessTokenLookup();
        accessTokenLookup.save(expired);
        AccessTokenService accessTokenService = new AccessTokenService(
                accessTokenLookup, fileService, DEFAULT_TTL_CEILING_SECONDS, DEFAULT_TTL_CEILING_SECONDS);

        assertThatThrownBy(() -> accessTokenService.redeem("expired-token-value"))
                .isInstanceOf(AccessTokenNotFoundException.class);
    }

    private File uploadFile(TenantId tenantId, Actor actor, Visibility visibility, byte[] content) {
        entityManager.getTransaction().begin();
        Folder folder = folderService.create(tenantId, actor, "Docs", null);
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        File file = fileService.upload(tenantId, actor, folder.getId(), "photo.png", visibility,
                new ByteArrayInputStream(content), content.length, "image/png");
        entityManager.getTransaction().commit();
        return file;
    }

    /** Captures what it was asked to save, and answers lookups from a preloaded map. */
    private static final class FakeAccessTokenLookup implements AccessTokenLookup {
        private final Map<String, AccessToken> byToken = new HashMap<>();
        private AccessToken saved;

        @Override
        public AccessToken save(AccessToken accessToken) {
            this.saved = accessToken;
            byToken.put(accessToken.getToken(), accessToken);
            return accessToken;
        }

        @Override
        public Optional<AccessToken> findByToken(String token) {
            return Optional.ofNullable(byToken.get(token));
        }
    }

    /** A tiny in-memory {@link StorageBackend}: content actually round-trips, unlike a call-recording fake. */
    private static final class InMemoryStorageBackend implements StorageBackend {
        private final Map<String, byte[]> contents = new HashMap<>();

        @Override
        public String store(String key, InputStream content, long contentLength, String contentType) {
            try {
                contents.put(key, content.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return key;
        }

        @Override
        public InputStream retrieve(String reference) {
            byte[] bytes = contents.get(reference);
            if (bytes == null) {
                throw new AssertionError("no content stored for reference " + reference);
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void delete(String reference) {
            contents.remove(reference);
        }

        @Override
        public boolean exists(String reference) {
            return contents.containsKey(reference);
        }
    }
}
