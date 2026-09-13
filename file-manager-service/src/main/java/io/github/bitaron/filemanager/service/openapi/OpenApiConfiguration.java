package io.github.bitaron.filemanager.service.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger/OpenAPI metadata (docs/api-design.md: "document every endpoint... this is a v1
 * requirement, not optional polish"), plus the ApiKey bearer scheme every {@code /api/v1}
 * operation is implicitly protected by ({@code ApiKeyAuthenticationFilter} enforces it; this
 * bean only documents that fact for Swagger UI/OpenAPI consumers).
 */
@Configuration
class OpenApiConfiguration {

    private static final String API_KEY_SECURITY_SCHEME = "ApiKey";

    @Bean
    OpenAPI fileManagerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("File Manager - Standalone Service")
                        .version("v1")
                        .description(
                                "Multi-tenant, Drive-like hierarchical file management, exposed "
                                        + "over a versioned REST API."))
                .components(new Components()
                        .addSecuritySchemes(
                                API_KEY_SECURITY_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .description(
                                                "An ApiKey secret in the fm_<43 base64url chars> "
                                                        + "format, presented as Authorization: "
                                                        + "Bearer <secret>.")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SECURITY_SCHEME));
    }
}
