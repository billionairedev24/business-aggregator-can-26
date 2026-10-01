package ca.northline.shared.web;

import ca.northline.shared.security.CurrentStaff;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves a {@link CurrentStaff} parameter on a {@code @RequiresConsole} handler (set by the interceptor). */
class CurrentStaffArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == CurrentStaff.class;
    }

    @Override
    public CurrentStaff resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory) {
        if (webRequest.getAttribute(StaffAccessInterceptor.CURRENT_STAFF, RequestAttributes.SCOPE_REQUEST)
                instanceof CurrentStaff staff) {
            return staff;
        }
        throw new IllegalStateException(
                "CurrentStaff parameter on %s, which isn't a @RequiresConsole handler under /api/v1/console"
                        .formatted(parameter.getExecutable()));
    }
}
