package io.github.bitaron.filemanager.core.accesstoken;

/**
 * The kind of Non-secure Access an {@link AccessToken} was minted for (see the glossary in
 * {@code CONTEXT.md}): {@code VIEW} drives an inline {@code Content-Disposition} on redemption,
 * {@code DOWNLOAD} an attachment one. A closed two-value set, so a plain enum is the idiomatic
 * immutable value type here (same precedent as {@link io.github.bitaron.filemanager.core.file.Visibility}) -
 * no need to force a record wrapper (`VARCHAR` column per ADR 0003).
 */
public enum Purpose {
    VIEW,
    DOWNLOAD
}
