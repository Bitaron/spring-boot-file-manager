package io.github.bitaron.filemanager.service.security;

import io.github.bitaron.filemanager.core.Actor;
import io.github.bitaron.filemanager.core.tenant.TenantId;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Injects the {@link TenantId}/{@link Actor} {@link ApiKeyAuthenticationFilter} resolved for this
 * request directly as controller method parameters - controllers ask for "the caller's Tenant"
 * the same way {@code file-manager-core}'s own methods do, with no manual request-attribute
 * lookup in controller bodies.
 */
public class SecureAccessArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return type == TenantId.class || type == Actor.class;
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        String attributeName = parameter.getParameterType() == TenantId.class
                ? ApiKeyAuthenticationFilter.TENANT_ID_ATTRIBUTE
                : ApiKeyAuthenticationFilter.ACTOR_ATTRIBUTE;
        Object value = webRequest.getAttribute(attributeName, NativeWebRequest.SCOPE_REQUEST);
        if (value == null) {
            // Only reachable if this resolver is wired to a route ApiKeyAuthenticationFilter
            // doesn't guard - a wiring bug, not a runtime/request condition, hence unchecked.
            throw new IllegalStateException(
                    "No " + parameter.getParameterType().getSimpleName()
                            + " resolved for this request - is it behind ApiKeyAuthenticationFilter?");
        }
        return value;
    }
}
