package io.github.bitaron.filemanager.service.web;

import io.github.bitaron.filemanager.core.accesstoken.AccessTokenRedemption;
import io.github.bitaron.filemanager.core.accesstoken.AccessTokenService;
import io.github.bitaron.filemanager.core.accesstoken.Purpose;
import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.file.FileContent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redeems an AccessToken for Non-secure Access (issue #36): {@code GET /access/{token}}, deliberately
 * outside {@code /api/v1} per ADR 0004 - not part of the Secure Access surface {@link FileController}
 * covers. Unauthenticated: {@code ApiKeyAuthenticationFilter.shouldNotFilter} already exempts any
 * path not starting with {@code /api/v1/}, so no filter change is needed for this route to be
 * reachable with no ApiKey. No {@code TenantId}/{@code Actor} parameters, unlike every {@code
 * /api/v1} controller - there's no caller identity to resolve here; the token itself is the whole
 * credential (decision #14/#25).
 *
 * <p>An expired or never-existent token both 404 identically (ADR 0004's non-enumerability rule) -
 * {@link AccessTokenService#redeem} throws the same {@code AccessTokenNotFoundException} for both,
 * mapped by {@code ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/access")
@Tag(name = "AccessTokens")
class AccessTokenController {

    private final AccessTokenService accessTokenService;

    /**
     * {@code @Lazy} for the same reason {@link FileController}'s own {@link AccessTokenService}
     * injection point is: a plain constructor-injected dependency would force Spring to eagerly
     * resolve it (and everything it transitively needs - a {@code FileService}, a
     * {@code StorageBackend}) the moment this singleton controller is created during context
     * refresh, in hosts that never configured a {@code StorageBackend}.
     */
    AccessTokenController(@Lazy AccessTokenService accessTokenService) {
        this.accessTokenService = accessTokenService;
    }

    @GetMapping("/{token}")
    @Operation(
            summary = "Redeem an AccessToken",
            description = "Unauthenticated, reusable until expiry. Content-Disposition is inline "
                    + "for a VIEW-purpose token, attachment for DOWNLOAD. An expired or "
                    + "nonexistent token both 404 identically.")
    ResponseEntity<InputStreamResource> redeem(@PathVariable String token) {
        AccessTokenRedemption redemption = accessTokenService.redeem(token);
        FileContent fileContent = redemption.fileContent();
        File file = fileContent.file();

        boolean inline = redemption.purpose() == Purpose.VIEW;
        String contentDisposition = ContentDispositionSupport.build(inline, file.getName());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(new InputStreamResource(fileContent.content()));
    }
}
