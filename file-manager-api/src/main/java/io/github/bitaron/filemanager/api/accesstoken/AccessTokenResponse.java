package io.github.bitaron.filemanager.api.accesstoken;

import java.time.Instant;

/**
 * The wire shape of a freshly-minted AccessToken, returned by
 * {@code POST /api/v1/files/{id}/access-tokens} (ADR 0004's "AccessToken minting response"
 * section). {@code url} is the constructed, ready-to-use relative redemption URL
 * ({@code /access/{token}}) - included alongside the bare {@code token} so a client never has to
 * reconstruct that convention itself.
 */
public record AccessTokenResponse(String token, Instant expiresAt, String url) {
}
