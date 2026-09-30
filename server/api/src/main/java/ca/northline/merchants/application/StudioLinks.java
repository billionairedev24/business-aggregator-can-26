package ca.northline.merchants.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the Studio lives ({@code northline.studio.base-url}, default {@code http://localhost:3100}): team invitation
 * links ({@code /invite/<token>}), the Stripe onboarding return URL and the Stripe Identity return URLs (S-22).
 */
@ConfigurationProperties("northline.studio")
public record StudioLinks(@Nullable String baseUrl) {

    public String origin() {
        var url = baseUrl == null || baseUrl.isBlank() ? "http://localhost:3100" : baseUrl.strip();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String invitation(String token) {
        return origin() + "/invite/" + token;
    }

    public String compliance(String merchantId) {
        return origin() + "/b/" + merchantId + "/compliance";
    }

    /** The onboarding wizard's Verification step of a business (Stripe Identity sends the signed-in owner back here). */
    public String onboardingVerification(String merchantId) {
        return origin() + "/onboarding/verification?m=" + merchantId;
    }

    /** Public "thanks, you're done" page for owners who verified from an emailed link (they may have no account). */
    public String identityDone() {
        return origin() + "/identity/done";
    }
}
