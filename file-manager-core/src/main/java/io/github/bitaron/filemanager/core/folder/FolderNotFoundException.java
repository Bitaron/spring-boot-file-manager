package io.github.bitaron.filemanager.core.folder;

/**
 * Thrown when a Folder id doesn't resolve for the calling Tenant - either it never existed or it
 * belongs to a different Tenant, which {@link FolderLookup#findByIdForTenant} deliberately can't
 * tell apart (docs/java-conventions.md's Tenant-isolation rule). A subtype of
 * {@link IllegalArgumentException} rather than a sibling type, so existing callers asserting on
 * {@code IllegalArgumentException} (docs/testing.md) keep working unchanged; it exists so
 * REST-layer callers (ADR 0004: cross-tenant access must be {@code 404}, never {@code 403}) can
 * distinguish "no such Folder" from a plain argument-validation failure (which maps to
 * {@code 400}) without parsing exception messages. The constructor is public so the Standalone
 * Service can also throw it directly for {@link FolderService#fetch}'s "returns {@code null}"
 * contract (Embedded Mode's preferred shape - a REST {@code GET} has no such option and must
 * throw to produce a {@code 404}).
 */
public class FolderNotFoundException extends IllegalArgumentException {

    public FolderNotFoundException(String message) {
        super(message);
    }
}
