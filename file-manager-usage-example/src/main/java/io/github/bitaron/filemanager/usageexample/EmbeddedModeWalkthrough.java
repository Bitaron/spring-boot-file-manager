package io.github.bitaron.filemanager.usageexample;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.file.FileContent;
import io.github.bitaron.filemanager.core.file.FileService;
import io.github.bitaron.filemanager.core.file.Visibility;
import io.github.bitaron.filemanager.core.folder.Folder;
import io.github.bitaron.filemanager.core.folder.FolderService;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import io.github.bitaron.filemanager.core.trash.TrashItem;
import io.github.bitaron.filemanager.core.trash.TrashService;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The living reference this module exists for (issue #12/#66): every call below mirrors a
 * documented snippet in docs/library-usage.md. If a future change to file-manager-core's
 * services breaks compilation or behavior here, this module's own test fails - the whole point
 * of a compiled, running example over a doc that's just prose with code blocks in it.
 *
 * <p>{@code @Transactional} on {@link #run()} is this module playing the "host application" role
 * docs/library-usage.md describes: {@code FolderService}/{@code FileService} never open their
 * own transaction, so whoever calls them must. This only works because {@link #run()} is invoked
 * externally, through this bean's Spring proxy (by {@link UsageExampleRunner}, a different bean)
 * - a self-call from within the same class would bypass the proxy and silently run without a
 * transaction.
 */
@Component
public class EmbeddedModeWalkthrough {

    private final FolderService folderService;
    private final FileService fileService;
    private final TrashService trashService;

    public EmbeddedModeWalkthrough(
            FolderService folderService, FileService fileService, TrashService trashService) {
        this.folderService = folderService;
        this.fileService = fileService;
        this.trashService = trashService;
    }

    @Transactional
    public void run() {
        // A real host application resolves these from its own Tenant provisioning (decision #6:
        // Tenant rows are seeded via SQL, not created through file-manager-core) and its own
        // ApiKey (Actor is an ApiKey's id in v1). Neither needs to exist as a persisted row for
        // this walkthrough - file-manager-core takes both as opaque, explicit parameters and
        // never inferred from any Tenant/ApiKey table.
        TenantId tenantId = new TenantId(UUID.randomUUID());
        Actor actor = new Actor(UUID.randomUUID());

        step("Create a top-level Folder");
        Folder reports = folderService.create(tenantId, actor, "Quarterly Reports", null);
        System.out.println("  created Folder " + reports.getId() + " \"" + reports.getName() + "\"");

        step("Create a nested Folder");
        Folder q1 = folderService.create(tenantId, actor, "2026 Q1", reports.getId());
        System.out.println("  created Folder " + q1.getId() + " \"" + q1.getName() + "\" under " + reports.getId());

        step("Fetch and list Folders");
        Folder fetched = folderService.fetch(tenantId, reports.getId());
        System.out.println("  fetched \"" + fetched.getName() + "\"");
        List<Folder> children = folderService.listChildren(tenantId, reports.getId());
        System.out.println("  \"" + reports.getName() + "\"'s children: "
                + children.stream().map(Folder::getName).toList());

        step("Upload a File into the nested Folder");
        byte[] content = "Hello from file-manager!".getBytes(StandardCharsets.UTF_8);
        File hello = fileService.upload(tenantId, actor, q1.getId(), "hello.txt", Visibility.PRIVATE,
                new ByteArrayInputStream(content), content.length, "text/plain");
        System.out.println("  uploaded File " + hello.getId() + " \"" + hello.getName() + "\" ("
                + hello.getSize() + " bytes)");

        step("Download that File's content back");
        FileContent downloaded = fileService.fetchContent(tenantId, hello.getId());
        try (var in = downloaded.content()) {
            String roundTripped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("  read back: \"" + roundTripped + "\"");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        step("Trash the File, then look at the unified Trash bin");
        fileService.trash(tenantId, actor, hello.getId());
        List<TrashItem> trashed = trashService.list(tenantId, null);
        System.out.println("  trash bin now holds " + trashed.size() + " item(s): "
                + trashed.stream().map(EmbeddedModeWalkthrough::describe).toList());

        step("Restore the File, and confirm the Trash bin is empty again");
        fileService.restore(tenantId, actor, hello.getId());
        System.out.println("  trash bin now holds " + trashService.list(tenantId, null).size() + " item(s)");

        System.out.println();
        System.out.println("Done - see docs/library-usage.md for the full, prose version of this walkthrough.");
    }

    private static String describe(TrashItem item) {
        return switch (item) {
            case TrashItem.TrashedFolder trashedFolder -> "Folder \"" + trashedFolder.folder().getName() + "\"";
            case TrashItem.TrashedFile trashedFile -> "File \"" + trashedFile.file().getName() + "\"";
        };
    }

    private static void step(String description) {
        System.out.println();
        System.out.println("-- " + description + " --");
    }
}
