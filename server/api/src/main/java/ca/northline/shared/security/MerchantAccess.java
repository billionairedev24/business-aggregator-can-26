package ca.northline.shared.security;

import static ca.northline.shared.security.MerchantAccessDenied.Reason.INSUFFICIENT_ROLE;
import static ca.northline.shared.security.MerchantAccessDenied.Reason.MFA_REQUIRED;
import static ca.northline.shared.security.MerchantAccessDenied.Reason.NOT_A_MEMBER;
import static ca.northline.shared.security.MerchantAccessDenied.Reason.NOT_BOUND;
import static ca.northline.shared.security.MerchantAccessDenied.Reason.PARTNER_NOT_ALLOWED;

import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Merchant-scoped authorization. Web handlers use {@link RequiresMerchant} (enforced by an interceptor that calls
 * {@link #require}); application code can call {@link #require} directly, and SpEL can use
 * {@code @merchantAccess.has(#merchantId, 'EDIT')}.
 *
 * <p>Membership is read from the database on every check (not from the {@code merchants} token claim) so removed
 * staff lose access immediately, not when their 10-minute token expires.
 */
@Component("merchantAccess")
@RequiredArgsConstructor
public class MerchantAccess {

    private final ObjectProvider<MerchantMemberships> memberships;

    /** The authenticated caller; throws (→ 401) if there is none. */
    public CurrentUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("Not signed in");
        }
        return toCurrentUser(auth);
    }

    /** Authorizes the caller for {@code permission} in {@code merchantId}, or throws {@link MerchantAccessDenied}. */
    public CurrentMember require(String merchantId, MerchantPermission permission) {
        var user = currentUser();
        if (!user.mfa()) {
            throw new MerchantAccessDenied(
                    MFA_REQUIRED,
                    "Business access needs a second factor. Sign in again with your passkey or authenticator app.");
        }
        var role = memberships
                .getObject()
                .roleOf(merchantId, user.userId())
                .orElseThrow(() -> new MerchantAccessDenied(NOT_A_MEMBER, "You are not a member of this business."));
        if (!role.grants(permission)) {
            throw new MerchantAccessDenied(INSUFFICIENT_ROLE, "Your role (%s) can't do this.".formatted(role.code()));
        }
        return new CurrentMember(merchantId, user.userId(), role);
    }

    /** Whether the caller is a partner client (S-30) rather than a person. */
    public boolean isPartner() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.getAuthorities().stream().anyMatch(a -> Authorities.PARTNER.equals(a.getAuthority()));
    }

    /**
     * S-30: authorizes a partner for {@code merchantId} on an endpoint marked {@link PartnerAccess}: the business must be
     * in the token's {@code "merchants"} claim (the partner's binding) and the token must carry the scope. Partners have
     * no membership and never get a {@link CurrentMember}.
     */
    public void requirePartner(String merchantId, PartnerAccess access) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var authorities = auth == null
                ? Set.<String>of()
                : auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());
        if (!authorities.contains(Authorities.SCOPE_PREFIX + access.value())) {
            throw new MerchantAccessDenied(
                    PARTNER_NOT_ALLOWED, "This partner token lacks the %s scope.".formatted(access.value()));
        }
        if (!authorities.contains(Authorities.MERCHANT_PREFIX + merchantId)) {
            throw new MerchantAccessDenied(NOT_BOUND, "This partner doesn't act for this business.");
        }
    }

    /** Boolean form for SpEL ({@code @PreAuthorize("@merchantAccess.has(#merchantId, 'EDIT')")}). */
    public boolean has(String merchantId, String permission) {
        try {
            require(merchantId, MerchantPermission.valueOf(permission));
            return true;
        } catch (MerchantAccessDenied _) {
            return false;
        }
    }

    static CurrentUser toCurrentUser(Authentication auth) {
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return new CurrentUser(
                auth.getName(),
                stripped(authorities, Authorities.SCOPE_PREFIX),
                stripped(authorities, Authorities.ROLE_PREFIX),
                authorities.contains(Authorities.MFA));
    }

    private static Set<String> stripped(Set<String> authorities, String prefix) {
        return authorities.stream()
                .filter(a -> a.startsWith(prefix))
                .map(a -> a.substring(prefix.length()))
                .collect(Collectors.toUnmodifiableSet());
    }
}
