/**
 * The {@code StorageBackend} contract: {@code store}/{@code retrieve}/{@code delete}/
 * {@code exists} over an opaque storage reference (ADR 0002). Stays free of any specific
 * backend's SDK types - implementations live in their own {@code file-manager-storage-*} module.
 */
package io.github.bitaron.filemanager.storage;
