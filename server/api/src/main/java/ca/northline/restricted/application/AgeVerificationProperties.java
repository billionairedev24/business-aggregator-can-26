package ca.northline.restricted.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.age-verification.*}: the identity provider ({@code AGE_VERIFICATION_PROVIDER}: {@code local |
 * stripe}) and where it sends the customer back — the consumer site ({@code CONSUMER_ORIGIN}: its shop checkout or
 * food checkout), or the consumer app ({@code AGE_VERIFICATION_APP_RETURN_URL}, the app's scheme). Fixed paths only:
 * the request names a place, never a URL (no open redirect).
 */
@ConfigurationProperties("northline.age-verification")
public record AgeVerificationProperties(
        @Nullable String provider,
        @DefaultValue("http://localhost:3000") String webOrigin,
        @DefaultValue("ca.northline.app:/age-verified") String appReturnUrl) {

    public String webReturnUrl(String path) {
        var origin = webOrigin.endsWith("/") ? webOrigin.substring(0, webOrigin.length() - 1) : webOrigin;
        return origin + path;
    }

    public String effectiveProvider() {
        return provider == null || provider.isBlank()
                ? "local"
                : provider.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
