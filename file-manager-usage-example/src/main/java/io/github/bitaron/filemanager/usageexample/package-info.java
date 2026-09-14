/**
 * A living Embedded Mode reference (issue #12/#66): a runnable Spring Boot app, not a library
 * anything else in the reactor depends on. Walks through the calls documented in
 * docs/library-usage.md against a real embedded database and local-disk {@code StorageBackend},
 * so that doc's snippets can't silently drift, and so a human or agent integrating
 * file-manager-spring-boot-starter has something runnable to copy from. See this module's own
 * README.md for how to run it.
 */
package io.github.bitaron.filemanager.usageexample;
