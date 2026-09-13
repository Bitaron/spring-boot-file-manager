package io.github.bitaron.filemanager.service.web;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import io.github.bitaron.filemanager.core.accesstoken.AccessTokenNotFoundException;
import io.github.bitaron.filemanager.core.accesstoken.AccessTokenRedemption;
import io.github.bitaron.filemanager.core.accesstoken.AccessTokenService;
import io.github.bitaron.filemanager.core.accesstoken.Purpose;
import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.file.FileContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice-level test for {@code GET /access/{token}} (issue #36): mounts only {@link
 * AccessTokenController} in a standalone {@link MockMvc} instance (no full {@code
 * ApplicationContext}) with a mocked {@link AccessTokenService} - proving this controller's own
 * request/response mapping (Purpose-driven {@code Content-Disposition}, the shared 404 mapping),
 * not {@link AccessTokenService#redeem}'s own behavior ({@code AccessTokenServiceTest} in {@code
 * file-manager-core} already covers that) and not the "no ApiKey required" property (already
 * proven statically by {@code ApiKeyAuthenticationFilter.shouldNotFilter} excluding anything
 * outside {@code /api/v1/}, unchanged by this ticket). A full, real end-to-end redemption
 * round-trip belongs to a later {@code AccessTokenControllerIntegrationTest} (docs/testing.md).
 *
 */
class AccessTokenControllerTest {

    private AccessTokenService accessTokenService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accessTokenService = mock(AccessTokenService.class);
        AccessTokenController controller = new AccessTokenController(accessTokenService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void redeemingAViewPurposeTokenReturns200WithInlineContentDispositionAndTheFileBytes() throws Exception {
        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
        File file = mock(File.class);
        when(file.getName()).thenReturn("note.txt");
        when(file.getContentType()).thenReturn("text/plain");
        when(accessTokenService.redeem("tok-view"))
                .thenReturn(new AccessTokenRedemption(Purpose.VIEW, new FileContent(file, new ByteArrayInputStream(bytes))));

        mockMvc.perform(get("/access/{token}", "tok-view"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
                .andExpect(result -> {
                    String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
                    org.assertj.core.api.Assertions.assertThat(disposition).startsWith("inline");
                    org.assertj.core.api.Assertions.assertThat(disposition).contains("note.txt");
                })
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsByteArray())
                        .isEqualTo(bytes));
    }

    @Test
    void redeemingADownloadPurposeTokenReturns200WithAttachmentContentDisposition() throws Exception {
        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
        File file = mock(File.class);
        when(file.getName()).thenReturn("report.pdf");
        when(file.getContentType()).thenReturn("application/pdf");
        when(accessTokenService.redeem("tok-download")).thenReturn(
                new AccessTokenRedemption(Purpose.DOWNLOAD, new FileContent(file, new ByteArrayInputStream(bytes))));

        mockMvc.perform(get("/access/{token}", "tok-download"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
                    org.assertj.core.api.Assertions.assertThat(disposition).startsWith("attachment");
                    org.assertj.core.api.Assertions.assertThat(disposition).contains("report.pdf");
                });
    }

    @Test
    void redeemingANonexistentOrExpiredTokenReturns404WithTheFixedNotFoundBody() throws Exception {
        when(accessTokenService.redeem("bad-token"))
                .thenThrow(new AccessTokenNotFoundException("No live AccessToken exists for this token"));

        mockMvc.perform(get("/access/{token}", "bad-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Not found"));
    }
}
