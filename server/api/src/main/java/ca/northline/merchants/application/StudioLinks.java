package ca.northline.merchants.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the Studio lives ({@code northline.studio.base-url}, default {@code http://localhost:3100}): team invitation
 * links ({@code /invite/<token>}) and the Stripe onboarding return URL.
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
}
