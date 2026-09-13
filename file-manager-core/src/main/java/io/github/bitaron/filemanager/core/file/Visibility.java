package io.github.bitaron.filemanager.core.file;

/**
 * Whether a File is reachable only via Secure Access (an ApiKey-authenticated call) or also via
 * Non-secure Access (an unauthenticated AccessToken link) - see the glossary in
 * {@code CONTEXT.md}. A closed two-value set, so a plain enum is the idiomatic immutable value
 * type here (docs/java-conventions.md) - no need to force a record wrapper as with
 * {@code Purpose}/{@code AccessToken}.
 */
public enum Visibility {
    PUBLIC,
    PRIVATE
}
