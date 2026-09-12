/**
 * Embedded Mode's Spring Boot autoconfigure: wires {@code file-manager-core} into a host
 * application's own persistence context and a configured {@code StorageBackend}, selected via
 * the single {@code file-manager.storage.type} property (see issue #17's decision).
 */
package io.github.bitaron.filemanager.autoconfigure;
