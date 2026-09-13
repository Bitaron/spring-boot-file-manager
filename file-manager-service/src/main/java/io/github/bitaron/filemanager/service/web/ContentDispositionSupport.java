package io.github.bitaron.filemanager.service.web;

import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;

/**
 * Builds the {@code Content-Disposition} header value shared by both of this module's Non-secure
 * and Secure content-delivery endpoints: {@link FileController#download} (Secure Access) and
 * {@link AccessTokenController#redeem} (Non-secure Access, driven by the redeemed token's {@code
 * Purpose}). Extracted here once a second caller ({@code AccessTokenController}) needed the exact
 * same RFC 5987 encoding rule {@code FileController.download} already had - not duplicated inline a
 * second time.
 */
final class ContentDispositionSupport {

    private ContentDispositionSupport() {
    }

    /**
     * @param inline {@code true} for {@code inline} (render in-browser), {@code false} for {@code
     *     attachment} (force a download)
     * @param filename encoded via the {@code (String, Charset)} overload - not the plain {@code
     *     filename(String)} one, which leaves the builder's charset null and writes non-ASCII names
     *     into the header as raw, un-encoded bytes (headers are effectively Latin-1)
     */
    static String build(boolean inline, String filename) {
        return (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString();
    }
}
