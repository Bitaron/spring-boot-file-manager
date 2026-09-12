package io.github.bitaron.filemanager.core.apikey;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes the {@code key_hash} column value for a presented ApiKey secret: a fast, deterministic
 * SHA-256 hex digest, not a slow/salted password hash. Per decision #15, an ApiKey is CSPRNG-
 * generated (nothing to brute-force by guessing) and verified on every Secure Access call, a hot
 * path where adaptive-hash latency (bcrypt/Argon2) buys nothing — the opposite trade-off from a
 * rarely-checked, human-chosen password.
 */
public final class ApiKeyHasher {

    private ApiKeyHasher() {
    }

    /** Hashes the full presented secret, prefix included - no need to strip it first. */
    public static String hash(String presentedSecret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(presentedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every JDK (see MessageDigest's javadoc).
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
