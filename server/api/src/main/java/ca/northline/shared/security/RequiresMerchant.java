package ca.northline.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Guards a handler under {@code /api/v1/merchants/{merchantId}/…}: the caller must be a member of that merchant whose
 * role grants {@link #value()}, and the token must carry {@code acr=mfa}. Otherwise HTTP 403 ProblemDetail with
 * {@code code} = {@code mfa_required} | {@code not_a_member} | {@code insufficient_role}.
 *
 * <p>Put it on the method (or on the controller class as a default). Every handler whose path contains
 * {@code {merchantId}} MUST carry it — handlers without it are denied at runtime and fail {@code MerchantScopedEndpointsTest}.
 *
 * <pre>{@code
 * @PatchMapping("/api/v1/merchants/{merchantId}")
 * @RequiresMerchant(MerchantPermission.MANAGE)
 * MerchantResponse rename(@PathVariable String merchantId, @Valid @RequestBody RenameRequest body, CurrentMember member)
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresMerchant {
    MerchantPermission value();
}
