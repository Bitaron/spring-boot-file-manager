package io.github.bitaron.filemanager.starter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.file.FileContent;
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
 * Embedded Mode integration test for File persistence + storage (mirrors
 * {@link FolderPersistenceIntegrationTest}, AGENT-BRIEF.md's "Slice 2: Embedded Mode wiring").
 * Boots with only a {@link DataSource} bean plus the {@code file-manager.storage.backend=local}
 * property supplied - the autoconfigure is expected to fold {@code File} into the host's own JPA
 * entity scan (no second/separate persistence unit) and expose a working {@link FileService} bean
 * wired against that same {@link DataSource}, {@link FolderService} (for the folder-existence
 * check), and a real {@code io.github.bitaron.filemanager.storage.local.LocalStorageBackend}
 * rooted at {@link #storageRoot} (a {@code @TempDir}) - so uploaded content really round-trips
 * through disk, not just metadata through the database.
 *
 * <p>{@code @Transactional}: file-manager-core's {@code FileService} never opens a transaction
 * itself - the caller manages transactions (docs/architecture.md, matching {@code FolderService}'s
 * own contract). Here the test method is the caller, so it's annotated {@code @Transactional} to
 * get a transactional shared {@link EntityManager} that actually flushes/persists against the
 * {@link DataSource}, rather than reaching for a {@code TransactionTemplate} explicitly.
 */
@SpringBootTest(
        classes = FilePersistenceIntegrationTest.TestApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "file-manager.storage.backend=local"
        })
@Transactional
class FilePersistenceIntegrationTest {

    /**
     * Static so {@link #storageProperties} (also static, per {@code @DynamicPropertySource}'s
     * contract) can read it while the {@code ApplicationContext} is still being prepared - JUnit
     * populates static {@code @TempDir} fields before Spring's dynamic property resolution runs.
     */
    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("file-manager.storage.local.root-directory", () -> storageRoot.toString());
    }

    @Autowired
    private FileService fileService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void uploadsAndDownloadsFileContentAgainstARealLocalStorageBackend() throws IOException {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        Folder folder = folderService.create(tenantId, actor, "Invoices", null);

        byte[] uploadedBytes = "Q1 invoice content".getBytes(StandardCharsets.UTF_8);

        // A null visibility must default to PRIVATE (issue #33's acceptance criterion),
        // exercised here too, not just at the core-unit-test level.
        File uploaded = fileService.upload(
                tenantId,
                actor,
                folder.getId(),
                "invoice.txt",
                null,
                new ByteArrayInputStream(uploadedBytes),
                uploadedBytes.length,
                "text/plain");

        // Flush and clear the persistence context so the lookups below hit the database rather
        // than returning the same managed instance still cached in memory.
        entityManager.flush();
        entityManager.clear();

        File reloaded = fileService.fetch(tenantId, uploaded.getId());
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getName()).isEqualTo("invoice.txt");
        assertThat(reloaded.getParentFolderId()).isEqualTo(folder.getId());
        assertThat(reloaded.getVisibility()).isEqualTo(Visibility.PRIVATE);

        FileContent downloaded = fileService.fetchContent(tenantId, uploaded.getId());
        assertThat(downloaded.file().getContentType()).isEqualTo("text/plain");
        try (InputStream content = downloaded.content()) {
            assertThat(content.readAllBytes()).isEqualTo(uploadedBytes);
        }
    }

    /**
     * Mirrors {@link FolderPersistenceIntegrationTest#fetchesAndListsFoldersAgainstTheHostDataSource},
     * adapted for File's own {@code listFiles} seam.
     */
    @Test
    void listsFilesAgainstTheHostDataSource() throws IOException {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        Folder folder = folderService.create(tenantId, actor, "Invoices", null);
        Folder otherFolder = folderService.create(tenantId, actor, "Receipts", null);

        File child = uploadFile(tenantId, actor, folder.getId(), "invoice.txt", "invoice content");
        uploadFile(tenantId, actor, otherFolder.getId(), "receipt.txt", "receipt content");

        entityManager.flush();
        entityManager.clear();

        List<File> children = fileService.listFiles(tenantId, folder.getId());
        assertThat(children).extracting(File::getId).containsExactly(child.getId());
    }

    /**
     * Mirrors {@link FolderPersistenceIntegrationTest#renamesFolderAgainstTheHostDataSource}.
     */
    @Test
    void renamesFileAgainstTheHostDataSource() throws IOException {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        Folder folder = folderService.create(tenantId, actor, "Invoices", null);
        File uploaded = uploadFile(tenantId, actor, folder.getId(), "invoice.txt", "invoice content");

        fileService.rename(tenantId, actor, uploaded.getId(), "receipt.txt");

        entityManager.flush();
        entityManager.clear();
        File reloaded = entityManager.find(File.class, uploaded.getId());

        assertThat(reloaded.getName()).isEqualTo("receipt.txt");
    }

    /**
     * Mirrors {@link FolderPersistenceIntegrationTest#movesFolderToADifferentParentAndThenToTopLevelAgainstTheHostDataSource},
     * minus the "move to top-level" half - a File can never be top-level (ADR 0003: every File
     * belongs to exactly one Folder), unlike Folder.
     */
    @Test
    void movesFileToADifferentParentAgainstTheHostDataSource() throws IOException {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());
        Folder oldParent = folderService.create(tenantId, actor, "2026 Q1", null);
        Folder newParent = folderService.create(tenantId, actor, "2026 Q2", null);
        File uploaded = uploadFile(tenantId, actor, oldParent.getId(), "invoice.txt", "invoice content");

        fileService.move(tenantId, actor, uploaded.getId(), newParent.getId());

        entityManager.flush();
        entityManager.clear();
        File reloadedAfterMove = entityManager.find(File.class, uploaded.getId());
        assertThat(reloadedAfterMove.getParentFolderId()).isEqualTo(newParent.getId());
    }

    private File uploadFile(TenantId tenantId, Actor actor, UUID folderId, String name, String content)
            throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            return fileService.upload(tenantId, actor, folderId, name, null, input, bytes.length, "text/plain");
        }
    }

    /**
     * Minimal host application: only a {@link DataSource} bean, no persistence.xml, no entity
     * scan of its own - everything else (entity scan registration, {@code EntityManagerFactory},
     * {@code FolderService}/{@code FileService} beans, the {@code StorageBackend} bean) is
     * expected to come from the file-manager autoconfigure modules once they exist.
     */
    @SpringBootApplication
    static class TestApplication {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:file-persistence-it;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }
    }
}
