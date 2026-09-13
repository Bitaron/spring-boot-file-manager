package io.github.bitaron.filemanager.service.security;

import java.io.IOException;

import io.github.bitaron.filemanager.core.apikey.ApiKeyResolution;
import io.github.bitaron.filemanager.core.apikey.ApiKeyResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves every {@code /api/v1/**} request's ApiKey to its Tenant + Actor before any Folder/File
 * lookup happens (docs/api-design.md's Authentication rule), via the same {@link ApiKeyResolver}
 * Embedded Mode can optionally use (decision #17) - one resolution mechanism, two callers.
 *
 * <p>Reads the secret from a standard {@code Authorization: Bearer <secret>} header - not
 * ADR-normative (no header convention is specified there), but the natural fit for a bearer-style
 * credential and consistent with {@link ApiKeySecret}'s own self-describing {@code fm_} prefix.
 * A missing, malformed, or revoked key all resolve to {@link ApiKeyResolution.NotAuthenticated}
 * indistinguishably (matching ADR 0004's single {@code 401} for all three) - this filter writes
 * that response directly rather than throwing, since exceptions raised in a servlet {@link
 * jakarta.servlet.Filter} never reach the {@code @RestControllerAdvice} that handles exceptions
 * thrown from inside controller methods.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String TENANT_ID_ATTRIBUTE = ApiKeyAuthenticationFilter.class.getName() + ".tenantId";
    static final String ACTOR_ATTRIBUTE = ApiKeyAuthenticationFilter.class.getName() + ".actor";

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyResolver apiKeyResolver;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ApiKeyResolver apiKeyResolver, ObjectMapper objectMapper) {
        this.apiKeyResolver = apiKeyResolver;
        this.objectMapper = objectMapper;
    }

    /** Only the authenticated REST surface is guarded - not Swagger UI/OpenAPI docs, for example. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        ApiKeyResolution resolution = apiKeyResolver.resolve(extractSecret(request));

        if (resolution instanceof ApiKeyResolution.Authenticated authenticated) {
            request.setAttribute(TENANT_ID_ATTRIBUTE, authenticated.tenantId());
            request.setAttribute(ACTOR_ATTRIBUTE, authenticated.actor());
            filterChain.doFilter(request, response);
            return;
        }

        writeUnauthorized(response);
    }

    private static String extractSecret(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length());
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Missing, malformed, or revoked ApiKey");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
