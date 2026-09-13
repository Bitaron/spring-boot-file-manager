package io.github.bitaron.filemanager.service.web;

import java.time.Instant;
import java.util.UUID;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.accesstoken.AccessToken;
import io.github.bitaron.filemanager.core.accesstoken.AccessTokenNotFoundException;
import io.github.bitaron.filemanager.core.accesstoken.AccessTokenService;
import io.github.bitaron.filemanager.core.accesstoken.Purpose;
import io.github.bitaron.filemanager.core.file.FileService;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice-level test for {@code POST /api/v1/files/{id}/access-tokens} (issue #36): mounts only
 * {@link FileController} in a standalone {@link MockMvc} instance (no full {@code
 * ApplicationContext}, no database, no {@code StorageBackend}) with a mocked {@link
 * AccessTokenService} - proving the controller's own request/response mapping (ADR 0004's route
 * table + "AccessToken minting response" section), not {@link AccessTokenService}'s own behavior
 * ({@code AccessTokenServiceTest} in {@code file-manager-core} already covers that). A full,
 * real end-to-end mint round-trip belongs to a later {@code AccessTokenControllerIntegrationTest}
 * (docs/testing.md), not here.
 *
 */
class FileControllerAccessTokenTest {

    private static final UUID FILE_ID = UUID.randomUUID();
    private static final TenantId TENANT_ID = new TenantId(UUID.randomUUID());
    private static final Actor ACTOR = new Actor(UUID.randomUUID());

    private AccessTokenService accessTokenService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FileService fileService = mock(FileService.class);
        accessTokenService = mock(AccessTokenService.class);
        FileController controller = new FileController(fileService, accessTokenService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new FixedIdentityArgumentResolver())
                .build();
    }

    @Test
    void mintingReturns200OkWithTokenExpiresAtAndTheConstructedRedemptionUrl() throws Exception {
        Instant expiresAt = Instant.parse("2026-01-01T00:15:00Z");
        AccessToken minted = mock(AccessToken.class);
        when(minted.getToken()).thenReturn("tok-abc123");
        when(minted.getExpiresAt()).thenReturn(expiresAt);
        when(accessTokenService.mint(eq(TENANT_ID), eq(ACTOR), eq(FILE_ID), eq(Purpose.VIEW), any()))
                .thenReturn(minted);

        mockMvc.perform(post("/api/v1/files/{id}/access-tokens", FILE_ID)
                        .contentType("application/json")
                        .content("{\"purpose\":\"VIEW\"}"))
                // ADR 0004: minting is an action on an existing resource -> 200, never 201.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("tok-abc123"))
                .andExpect(jsonPath("$.url").value("/access/tok-abc123"));
    }

    @Test
    void mintingPassesTheViewPurposeStringAsTheViewEnumValue() throws Exception {
        AccessToken minted = mock(AccessToken.class);
        when(minted.getToken()).thenReturn("tok-view");
        when(minted.getExpiresAt()).thenReturn(Instant.now());
        when(accessTokenService.mint(any(), any(), any(), any(), any())).thenReturn(minted);

        mockMvc.perform(post("/api/v1/files/{id}/access-tokens", FILE_ID)
                .contentType("application/json")
                .content("{\"purpose\":\"VIEW\"}"));

        verify(accessTokenService).mint(eq(TENANT_ID), eq(ACTOR), eq(FILE_ID), eq(Purpose.VIEW), eq(null));
    }

    @Test
    void mintingPassesTheDownloadPurposeStringAsTheDownloadEnumValue() throws Exception {
        AccessToken minted = mock(AccessToken.class);
        when(minted.getToken()).thenReturn("tok-download");
        when(minted.getExpiresAt()).thenReturn(Instant.now());
        when(accessTokenService.mint(any(), any(), any(), any(), any())).thenReturn(minted);

        mockMvc.perform(post("/api/v1/files/{id}/access-tokens", FILE_ID)
                .contentType("application/json")
                .content("{\"purpose\":\"DOWNLOAD\"}"));

        verify(accessTokenService).mint(eq(TENANT_ID), eq(ACTOR), eq(FILE_ID), eq(Purpose.DOWNLOAD), eq(null));
    }

    @Test
    void mintingPassesAnExplicitTtlSecondsThrough() throws Exception {
        AccessToken minted = mock(AccessToken.class);
        when(minted.getToken()).thenReturn("tok-ttl");
        when(minted.getExpiresAt()).thenReturn(Instant.now());
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        when(accessTokenService.mint(any(), any(), any(), any(), ttlCaptor.capture())).thenReturn(minted);

        mockMvc.perform(post("/api/v1/files/{id}/access-tokens", FILE_ID)
                .contentType("application/json")
                .content("{\"purpose\":\"VIEW\",\"ttlSeconds\":300}"));

        org.assertj.core.api.Assertions.assertThat(ttlCaptor.getValue()).isEqualTo(300L);
    }

    @Test
    void mintingAgainstANonPublicFileReturns404ViaAccessTokenNotFoundException() throws Exception {
        when(accessTokenService.mint(any(), any(), any(), any(), any()))
                .thenThrow(new AccessTokenNotFoundException("No Public File with id " + FILE_ID + " exists"));

        mockMvc.perform(post("/api/v1/files/{id}/access-tokens", FILE_ID)
                        .contentType("application/json")
                        .content("{\"purpose\":\"VIEW\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Not found"));
    }

    @Test
    void mintingWithAnInvalidPurposeStringReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/files/{id}/access-tokens", FILE_ID)
                        .contentType("application/json")
                        .content("{\"purpose\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());
    }

    /** Stands in for {@code SecureAccessArgumentResolver} without depending on the security package. */
    private static final class FixedIdentityArgumentResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == TenantId.class || parameter.getParameterType() == Actor.class;
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            return parameter.getParameterType() == TenantId.class ? TENANT_ID : ACTOR;
        }
    }
}
