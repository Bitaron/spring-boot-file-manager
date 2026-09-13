package io.github.bitaron.filemanager.starter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.accesstoken.AccessToken;
import io.github.bitaron.filemanager.core.accesstoken.AccessTokenRedemption;
import io.github.bitaron.filemanager.core.accesstoken.AccessTokenService;
import io.github.bitaron.filemanager.core.accesstoken.Purpose;
import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.file.FileService;
import io.github.bitaron.filemanager.core.file.Visibility;
import io.github.bitaron.filemanager.core.folder.Folder;
import io.github.bitaron.filemanager.core.folder.FolderService;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.EntityManager;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Embedded Mode integration test for AccessToken persistence (issue #36's Slice C, mirroring
 * {@link FilePersistenceIntegrationTest}'s pattern exactly). Boots with only a {@link DataSource}
 * bean plus the {@code file-manager.storage.backend=local} property supplied - the
 * {@code AccessTokenPersistenceAutoConfiguration} being introduced by this ticket is expected to
 * fold {@code AccessToken} into the host's own JPA entity scan (no second/separate persistence
 * unit) and expose a working {@link AccessTokenService} bean wired against that same
 * {@link DataSource}, {@link FileService} (for the mint-time Visibility check + redeem's
 * content-fetch), and the two {@code @Value}-injected per-Purpose TTL ceilings
 * ({@code file-manager.access-token.max-ttl-seconds.view} / {@code .download}).
 */
@SpringBootTest(
        classes = AccessTokenPersistenceIntegrationTest.TestApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "file-manager.storage.backend=local",
            // Deliberately overridden away from the 86400s default for DOWNLOAD only, so this test
            // proves the @Value ceiling is actually threaded into the bean (not just its default
            // literal) without touching the VIEW-purpose default-TTL assertion below, which relies
            // on the untouched 900s default being under the (untouched) 86400s VIEW ceiling.
            "file-manager.access-token.max-ttl-seconds.download=60"
        })
@Transactional
class AccessTokenPersistenceIntegrationTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("file-manager.storage.local.root-directory", () -> storageRoot.toString());
    }

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private FileService fileService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void mintsAndRedeemsAnAccessTokenAgainstARealDataSourceAndStorageBackend() throws IOException {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        Folder folder = folderService.create(tenantId, actor, "Public Assets", null);

        byte[] uploadedBytes = "public asset content".getBytes(StandardCharsets.UTF_8);
        File uploaded = fileService.upload(
                tenantId,
                actor,
                folder.getId(),
                "logo.png",
                Visibility.PUBLIC,
                new ByteArrayInputStream(uploadedBytes),
                uploadedBytes.length,
                "image/png");

        Instant beforeMint = Instant.now();
        AccessToken minted = accessTokenService.mint(tenantId, actor, uploaded.getId(), Purpose.VIEW, null);

        assertThat(minted.getToken()).isNotBlank();
        // Default TTL is 900s (decision #14); the VIEW ceiling here is untouched at its 86400s
        // default, so the default isn't clamped.
        assertThat(minted.getExpiresAt()).isAfter(beforeMint.plusSeconds(890));
        assertThat(minted.getExpiresAt()).isBefore(beforeMint.plusSeconds(910));

        entityManager.flush();
        entityManager.clear();

        AccessTokenRedemption redemption = accessTokenService.redeem(minted.getToken());

        assertThat(redemption.purpose()).isEqualTo(Purpose.VIEW);
        assertThat(redemption.fileContent().file().getContentType()).isEqualTo("image/png");
        try (InputStream content = redemption.fileContent().content()) {
            assertThat(content.readAllBytes()).isEqualTo(uploadedBytes);
        }
    }

    @Test
    void clampsAMintedTtlToTheConfiguredPerPurposeCeiling() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        Folder folder = folderService.create(tenantId, actor, "Public Assets", null);
        File uploaded = fileService.upload(
                tenantId,
                actor,
                folder.getId(),
                "report.pdf",
                Visibility.PUBLIC,
                new ByteArrayInputStream("report bytes".getBytes(StandardCharsets.UTF_8)),
                12,
                "application/pdf");

        Instant beforeMint = Instant.now();
        // Requests a TTL far beyond both the caller-requested value and the default - the
        // file-manager.access-token.max-ttl-seconds.download=60 override above must win.
        AccessToken minted = accessTokenService.mint(tenantId, actor, uploaded.getId(), Purpose.DOWNLOAD, 999_999L);

        assertThat(minted.getExpiresAt()).isAfter(beforeMint.plusSeconds(55));
        assertThat(minted.getExpiresAt()).isBefore(beforeMint.plusSeconds(65));
    }

    /**
     * Minimal host application: only a {@link DataSource} bean, no persistence.xml, no entity
     * scan of its own - everything else (entity scan registration, {@code EntityManagerFactory},
     * {@code FolderService}/{@code FileService}/{@code AccessTokenService} beans, the
     * {@code StorageBackend} bean, the TTL ceiling {@code @Value}s) is expected to come from the
     * file-manager autoconfigure modules once they exist.
     */
    @SpringBootApplication
    static class TestApplication {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:access-token-persistence-it;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }
    }
}
