package io.github.bitaron.filemanager.service.web;

import io.github.bitaron.filemanager.core.accesstoken.AccessTokenNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice-level test (no full Spring context - {@link MockMvcBuilders#standaloneSetup} wires only a
 * throwaway controller plus the real {@link ApiExceptionHandler}) proving issue #36's confirmed
 * decision: {@link AccessTokenNotFoundException} reaches {@link
 * ApiExceptionHandler#handleNotFound}, exactly like {@code FolderNotFoundException}/{@code
 * FileNotFoundException} already do - not {@link ApiExceptionHandler#handleBadRequest}, which
 * would apply if it were only matched as a plain {@link IllegalArgumentException} subtype.
 */
class ApiExceptionHandlerAccessTokenTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AccessTokenNotFoundThrowingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void accessTokenNotFoundExceptionMapsToA404WithTheFixedNotFoundBody() throws Exception {
        mockMvc.perform(get("/test-only/access-token-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Not found"));
    }

    @Test
    void accessTokenNotFoundExceptionResponseNeverLeaksTheExceptionMessage() throws Exception {
        mockMvc.perform(get("/test-only/access-token-not-found"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("super secret token detail"))));
    }

    @RestController
    private static class AccessTokenNotFoundThrowingController {
        @GetMapping("/test-only/access-token-not-found")
        String boom() {
            throw new AccessTokenNotFoundException("super secret token detail");
        }
    }
}
