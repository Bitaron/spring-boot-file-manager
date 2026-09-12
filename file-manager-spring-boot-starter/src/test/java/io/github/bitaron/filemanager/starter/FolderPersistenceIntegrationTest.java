package io.github.bitaron.filemanager.starter;

import java.util.UUID;

import javax.sql.DataSource;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.folder.Folder;
import io.github.bitaron.filemanager.core.folder.FolderService;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.EntityManager;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Embedded Mode integration test (docs/testing.md: "Embedded Mode gets its own integration test
 * that boots the Spring Boot starter in a plain (non-service) application context, proving the
 * autoconfigure works without the REST layer present"). Boots with only a {@link DataSource}
 * bean supplied - the autoconfigure is expected to fold {@link Folder} into the host's own JPA
 * entity scan (no second/separate persistence unit) and expose a working {@link FolderService}
 * bean wired against that same {@link DataSource}.
 *
 * <p>{@code @Transactional}: file-manager-core's {@code FolderService} never opens a transaction
 * itself - the caller manages transactions (docs/architecture.md, matching {@code FolderService}'s
 * own contract). Here the test method is the caller, so it's annotated {@code @Transactional} to
 * get a transactional shared {@link EntityManager} that actually flushes/persists against the
 * {@link DataSource}, rather than reaching for a {@code TransactionTemplate} explicitly.
 */
@SpringBootTest(
        classes = FolderPersistenceIntegrationTest.TestApplication.class,
        properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional
class FolderPersistenceIntegrationTest {

    @Autowired
    private FolderService folderService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void createsTopLevelFolderAndPersistsItAgainstTheHostDataSource() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        Folder created = folderService.create(tenantId, actor, "Quarterly Reports", null);

        // Flush and clear the persistence context so the lookup below hits the database rather
        // than returning the same managed instance still cached in memory.
        entityManager.flush();
        entityManager.clear();
        Folder reloaded = entityManager.find(Folder.class, created.getId());

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getParentFolderId()).isNull();
        assertThat(reloaded.tenantId()).isEqualTo(tenantId);
        assertThat(reloaded.getName()).isEqualTo("Quarterly Reports");
    }

    @Test
    void createsNestedFolderWithParentReferenceAgainstTheHostDataSource() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        Folder parent = folderService.create(tenantId, actor, "Quarterly Reports", null);
        Folder child = folderService.create(tenantId, actor, "2026 Q1", parent.getId());

        entityManager.flush();
        entityManager.clear();
        Folder reloadedChild = entityManager.find(Folder.class, child.getId());

        assertThat(reloadedChild).isNotNull();
        assertThat(reloadedChild.getParentFolderId()).isEqualTo(parent.getId());
    }

    /**
     * Minimal host application: only a {@link DataSource} bean, no persistence.xml, no entity
     * scan of its own - everything else (entity scan registration, {@code EntityManagerFactory},
     * {@code FolderService} bean) is expected to come from the file-manager autoconfigure once
     * it exists.
     */
    @SpringBootApplication
    static class TestApplication {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:folder-persistence-it;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }
    }
}
