package io.github.bitaron.filemanager.core.file;

/**
 * Thrown when a File id doesn't resolve for the calling Tenant - either it never existed or it
 * belongs to a different Tenant, which {@link FileLookup#findByIdForTenant} deliberately can't
 * tell apart (docs/java-conventions.md's Tenant-isolation rule). A subtype of
 * {@link IllegalArgumentException} rather than a sibling type, so existing callers asserting on
 * {@code IllegalArgumentException} (docs/testing.md) keep working unchanged; it exists so
 * REST-layer callers (ADR 0004: cross-tenant access must be {@code 404}, never {@code 403}) can
 * distinguish "no such File" from a plain argument-validation failure (which maps to {@code 400})
 * without parsing exception messages. Mirrors {@code core.folder.FolderNotFoundException} exactly.
 */
public class FileNotFoundException extends IllegalArgumentException {

    public FileNotFoundException(String message) {
        super(message);
    }
}
