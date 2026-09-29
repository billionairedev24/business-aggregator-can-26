package ca.northline.shared.web;

import ca.northline.shared.security.CurrentMember;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves a {@link CurrentMember} parameter; only valid on handlers guarded by {@code @RequiresMerchant}. */
class CurrentMemberArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == CurrentMember.class;
    }

    @Override
    public CurrentMember resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory) {
        if (webRequest.getAttribute(MerchantAccessInterceptor.CURRENT_MEMBER, RequestAttributes.SCOPE_REQUEST)
                instanceof CurrentMember member) {
            return member;
        }
        throw new IllegalStateException("CurrentMember parameter on %s needs @RequiresMerchant and a {merchantId} path"
                .formatted(parameter.getExecutable()));
    }
}
