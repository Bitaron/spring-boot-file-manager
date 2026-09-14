# File Manager - Usage Example

A runnable Embedded Mode consumer app. It exists so that both a human and an AI agent
integrating [`file-manager-spring-boot-starter`](../file-manager-spring-boot-starter) have
something to run and copy from, instead of only [`docs/library-usage.md`](../docs/library-usage.md)'s
prose. See that doc for the full explanation of what each call does and why; this module runs
the same calls, in the same order.

It's a leaf module - nothing in the reactor depends on it, and no external consumer ever adds it
as a dependency. It's built and tested by `mvn install` like every other module, but that's the
only thing it has in common with a library.

## What it demonstrates

[`EmbeddedModeWalkthrough`](src/main/java/io/github/bitaron/filemanager/usageexample/EmbeddedModeWalkthrough.java)
runs once at startup and walks through:

1. Creating a top-level Folder, then a nested Folder inside it.
2. Fetching a Folder and listing its children.
3. Uploading a File into a Folder.
4. Downloading that File's content back.
5. Trashing the File, checking the unified Trash bin, then restoring it.

That's the current scope of `docs/library-usage.md` (Folder CRUD, File upload/download,
Trash/restore). It's expected to grow alongside that doc - see issue #66.

## Running it

```
mvn -pl file-manager-usage-example -am spring-boot:run
```

No setup needed: an embedded H2 database and a local-disk `StorageBackend` (writing under
`${java.io.tmpdir}/file-manager-usage-example`) are both wired automatically - see
[`application.yml`](src/main/resources/application.yml). The app prints each step's result to
the console and then exits; there's no server to stop, since Embedded Mode never opens a socket.

To run it as a packaged jar instead:

```
mvn -pl file-manager-usage-example -am package
java -jar file-manager-usage-example/target/file-manager-usage-example.jar
```

## What's *not* here

No REST layer - that would blur this module with `file-manager-service` (the actual Standalone
Service). No S3/MinIO backend, no AccessTokens/Non-secure Access, no Purge - out of scope for the
first cut (issue #66); local-disk and the Folder/File/Trash surface only.
