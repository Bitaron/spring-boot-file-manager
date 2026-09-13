package io.github.bitaron.filemanager.service.security;

import java.util.List;

import io.github.bitaron.filemanager.core.apikey.ApiKeyResolver;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires {@link ApiKeyAuthenticationFilter} and {@link SecureAccessArgumentResolver} into the
 * Standalone Service's request pipeline. Depends on the {@link ApiKeyResolver} bean
 * {@code file-manager-spring-boot-autoconfigure}'s {@code ApiKeyPersistenceAutoConfiguration}
 * exposes once a {@code DataSource} is present - the same bean Embedded Mode can optionally use
 * (decision #17).
 */
@Configuration
public class SecureAccessWebConfiguration implements WebMvcConfigurer {

    private final ApiKeyResolver apiKeyResolver;
    private final ObjectMapper objectMapper;

    public SecureAccessWebConfiguration(ApiKeyResolver apiKeyResolver, ObjectMapper objectMapper) {
        this.apiKeyResolver = apiKeyResolver;
        this.objectMapper = objectMapper;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyAuthenticationFilter() {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(
                new ApiKeyAuthenticationFilter(apiKeyResolver, objectMapper));
        registration.addUrlPatterns("/api/v1/*");
        return registration;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new SecureAccessArgumentResolver());
    }
}
