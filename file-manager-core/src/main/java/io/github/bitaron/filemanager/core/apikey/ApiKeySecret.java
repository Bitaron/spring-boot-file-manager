package io.github.bitaron.filemanager.core.apikey;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The plaintext form of an ApiKey credential: a static, non-secret {@code fm_} prefix (so a
 * leaked value can be flagged by secret-scanning tools and referenced in logs without printing
 * the secret) followed by 256 bits of CSPRNG output, base64url-encoded without padding — 43
 * characters (see decision #15). Never persisted; only {@link ApiKeyHasher}'s digest of it is.
 */
public record ApiKeySecret(String value) {

    private static final String PREFIX = "fm_";
    private static final int SECRET_BYTES = 32;
    private static final Pattern FORMAT = Pattern.compile("^fm_[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public ApiKeySecret {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("ApiKey secret must match fm_<43 base64url chars>");
        }
    }

    /** Generates a new, CSPRNG-backed secret in the {@code fm_<43 base64url chars>} format. */
    public static ApiKeySecret generate() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new ApiKeySecret(PREFIX + encoded);
    }

    /** True if the given raw string is well-formed enough to even attempt a lookup. */
    public static boolean isWellFormed(String raw) {
        return raw != null && FORMAT.matcher(raw).matches();
    }
}
