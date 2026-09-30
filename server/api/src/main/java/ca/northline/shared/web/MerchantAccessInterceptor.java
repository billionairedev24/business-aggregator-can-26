package ca.northline.shared.web;

import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.MerchantAccess;
import ca.northline.shared.security.MerchantAccessDenied;
import ca.northline.shared.security.PartnerAccess;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Enforces {@link RequiresMerchant} for every handler whose URI template has a {@code {merchantId}} variable. Secure by
 * default: a {@code {merchantId}} handler without the annotation is denied. The authorized {@link CurrentMember} is
 * stored as a request attribute for {@link CurrentMemberArgumentResolver}.
 */
@Slf4j
@RequiredArgsConstructor
class MerchantAccessInterceptor implements HandlerInterceptor {

    static final String MERCHANT_ID = "merchantId";
    static final String CURRENT_MEMBER = CurrentMember.class.getName();

    private final MerchantAccess access;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        var required = requirement(method);
        var merchantId = uriVariables(request).get(MERCHANT_ID);
        if (merchantId == null) {
            if (required != null) {
                throw new IllegalStateException(
                        "@RequiresMerchant on %s but its path has no {merchantId}".formatted(method));
            }
            return true;
        }
        if (required == null) {
            log.error("Handler {} is under {{merchantId}} but has no @RequiresMerchant — denying.", method);
            throw new MerchantAccessDenied(
                    MerchantAccessDenied.Reason.UNGUARDED_ENDPOINT, "This endpoint is not available.");
        }
        if (access.isPartner()) { // S-30: partner clients only where a handler opts in
            var partner = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), PartnerAccess.class);
            if (partner == null) {
                throw new MerchantAccessDenied(
                        MerchantAccessDenied.Reason.PARTNER_NOT_ALLOWED, "This endpoint isn't open to partners.");
            }
            access.requirePartner(merchantId, partner);
            return true;
        }
        request.setAttribute(CURRENT_MEMBER, access.require(merchantId, required.value()));
        return true;
    }

    static @Nullable RequiresMerchant requirement(HandlerMethod method) {
        var onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresMerchant.class);
        return onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresMerchant.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> uriVariables(HttpServletRequest request) {
        var vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return vars instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }
}
