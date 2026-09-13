package io.github.bitaron.filemanager.core.accesstoken;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.file.FileContent;
import io.github.bitaron.filemanager.core.file.FileNotFoundException;
import io.github.bitaron.filemanager.core.file.FileService;
import io.github.bitaron.filemanager.core.file.Visibility;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import jakarta.persistence.EntityManager;

/**
 * Domain operations on {@link AccessToken}: minting one against a caller's own {@code Public} File
 * and redeeming one for Non-secure Access (docs/architecture.md's "service interfaces, not entity
 * setters" rule). Delegates persistence mechanics to an {@link AccessTokenLookup} and File
 * resolution/content-fetch to {@link FileService}, mirroring {@code core.file.FileService}.
 */
public class AccessTokenService {

    /** Default TTL (decision #14) when a caller mints without requesting one, in seconds. */
    private static final long DEFAULT_TTL_SECONDS = 900L;

    /** Token byte length before base64url-encoding - same CSPRNG approach as {@code ApiKeySecret}. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccessTokenLookup accessTokenLookup;
    private final FileService fileService;
    private final long viewTtlCeilingSeconds;
    private final long downloadTtlCeilingSeconds;

    /**
     * @param viewTtlCeilingSeconds the deployment-wide TTL ceiling, in seconds, for
     *     {@link Purpose#VIEW} tokens - a caller-requested TTL longer than this is capped
     *     (decision #14)
     * @param downloadTtlCeilingSeconds the same ceiling for {@link Purpose#DOWNLOAD} tokens
     */
    public AccessTokenService(EntityManager entityManager, FileService fileService,
            long viewTtlCeilingSeconds, long downloadTtlCeilingSeconds) {
        this(new AccessTokenDao(entityManager), fileService, viewTtlCeilingSeconds, downloadTtlCeilingSeconds);
    }

    AccessTokenService(AccessTokenLookup accessTokenLookup, FileService fileService,
            long viewTtlCeilingSeconds, long downloadTtlCeilingSeconds) {
        this.accessTokenLookup = accessTokenLookup;
        this.fileService = fileService;
        this.viewTtlCeilingSeconds = viewTtlCeilingSeconds;
        this.downloadTtlCeilingSeconds = downloadTtlCeilingSeconds;
    }

    /**
     * Mints a new AccessToken for {@code fileId}, owned by {@code actor}, scoped to
     * {@code purpose}.
     *
     * @param requestedTtlSeconds the caller-requested TTL, or {@code null} to accept the default
     *     (900s / 15 minutes); capped at the configured per-Purpose ceiling either way. Zero is
     *     accepted (mints an already-expired token); negative is rejected.
     * @throws IllegalArgumentException if a required argument is missing, or if
     *     {@code requestedTtlSeconds} is negative
     * @throws FileNotFoundException if no File with {@code fileId} exists for this Tenant
     * @throws AccessTokenNotFoundException if the File resolves but its {@link
     *     io.github.bitaron.filemanager.core.file.Visibility} is not {@code PUBLIC} (confirmed
     *     404, not 400 - issue #36)
     */
    public AccessToken mint(TenantId tenantId, Actor actor, UUID fileId, Purpose purpose, Long requestedTtlSeconds) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (fileId == null) {
            throw new IllegalArgumentException("fileId must not be null");
        }
        if (purpose == null) {
            throw new IllegalArgumentException("purpose must not be null");
        }
        // Zero is allowed (an immediately-expired token is a valid, if unusual, request - and the
        // one deterministic way callers have to produce one for testing); negative is never
        // meaningful and would otherwise mint an already-expired token with no error at all.
        if (requestedTtlSeconds != null && requestedTtlSeconds < 0) {
            throw new IllegalArgumentException("ttlSeconds must not be negative");
        }
        File file = fileService.fetch(tenantId, fileId);
        if (file == null) {
            throw new FileNotFoundException("No File with id " + fileId + " exists for this Tenant");
        }
        if (file.getVisibility() != Visibility.PUBLIC) {
            throw new AccessTokenNotFoundException("No Public File with id " + fileId + " exists for this Tenant");
        }
        long ceilingSeconds = purpose == Purpose.VIEW ? viewTtlCeilingSeconds : downloadTtlCeilingSeconds;
        long ttlSeconds = Math.min(requestedTtlSeconds == null ? DEFAULT_TTL_SECONDS : requestedTtlSeconds, ceilingSeconds);
        Instant mintedAt = Instant.now();
        AccessToken accessToken = new AccessToken(
                generateToken(), tenantId, fileId, purpose, mintedAt.plusSeconds(ttlSeconds), actor, mintedAt);
        return accessTokenLookup.save(accessToken);
    }

    /**
     * Redeems {@code token}: looks it up and, if it exists and hasn't expired, returns the
     * redeemed File's content together with the token's Purpose - via
     * {@link FileService}'s ungated, Non-secure-Access-only content-fetch seam, not
     * {@link FileService#fetchContent}, since that method now rejects {@code Public} Files.
     *
     * @throws AccessTokenNotFoundException if {@code token} doesn't resolve, or resolves but has
     *     expired - both cases produce this identical exception so a caller can never tell them
     *     apart (non-enumerability, ADR 0004)
     */
    public AccessTokenRedemption redeem(String token) {
        AccessToken accessToken = accessTokenLookup.findByToken(token)
                .filter(candidate -> candidate.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new AccessTokenNotFoundException("No live AccessToken exists for this token"));
        FileContent fileContent = fileService.fetchContentForNonSecureAccess(
                accessToken.tenantId(), accessToken.getFileId());
        return new AccessTokenRedemption(accessToken.getPurpose(), fileContent);
    }

    /** A CSPRNG-backed, base64url-encoded opaque token value - same shape rationale as {@code ApiKeySecret}. */
    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
