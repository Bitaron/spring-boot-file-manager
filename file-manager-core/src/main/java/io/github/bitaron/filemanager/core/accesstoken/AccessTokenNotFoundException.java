package io.github.bitaron.filemanager.core.accesstoken;

/**
 * Thrown whenever an AccessToken-mediated operation must produce the non-enumerable 404 confirmed
 * for this ticket (issue #36): the token doesn't exist, it exists but has expired, minting was
 * attempted against a non-{@code Public} File, or a Secure Access call was attempted against a
 * {@code Public} File. All four collapse to this single exception/status so a caller can never
 * distinguish "no such token" from "expired token" from "wrong Visibility" - distinguishing them
 * would leak information a non-enumerable credential is meant to hide.
 *
 * <p>A subtype of {@link IllegalArgumentException} rather than a sibling type, so existing callers
 * asserting on {@code IllegalArgumentException} (docs/testing.md) keep working unchanged; it exists
 * so REST-layer callers (ADR 0004: cross-tenant/non-enumerable access must be {@code 404}, never
 * {@code 403}/{@code 410}) can distinguish this from a plain argument-validation failure (which
 * maps to {@code 400}) without parsing exception messages. Mirrors
 * {@code core.file.FileNotFoundException} exactly.
 */
public class AccessTokenNotFoundException extends IllegalArgumentException {

    public AccessTokenNotFoundException(String message) {
        super(message);
    }
}
