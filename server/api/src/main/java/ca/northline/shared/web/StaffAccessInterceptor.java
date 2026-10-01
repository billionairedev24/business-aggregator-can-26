package ca.northline.shared.web;

import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.MerchantAccess;
import ca.northline.shared.security.RequiresConsole;
import ca.northline.shared.security.StaffAccess;
import ca.northline.shared.security.StaffAccessDenied;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresConsole} on every handler under {@code /api/v1/console/} (S-90). Secure by default: a console
 * handler without the annotation is denied. The authorized {@link CurrentStaff} is stored as a request attribute for
 * {@link CurrentStaffArgumentResolver}.
 */
@Slf4j
@RequiredArgsConstructor
class StaffAccessInterceptor implements HandlerInterceptor {

    static final String CURRENT_STAFF = CurrentStaff.class.getName();

    private final MerchantAccess users;
    private final StaffAccess staff;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        var required = requirement(method);
        if (required == null) {
            log.error("Handler {} is under /api/v1/console but has no @RequiresConsole — denying.", method);
            throw new StaffAccessDenied(
                    StaffAccessDenied.Reason.UNGUARDED_ENDPOINT, StaffAccessDenied.UNGUARDED_MESSAGE);
        }
        request.setAttribute(
                CURRENT_STAFF,
                staff.require(
                        users.currentUser(),
                        request.getHeader(StaffAccess.ROLE_VIEW_HEADER),
                        required.value(),
                        List.of(required.actions())));
        return true;
    }

    static @Nullable RequiresConsole requirement(HandlerMethod method) {
        var onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresConsole.class);
        return onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresConsole.class);
    }
}
