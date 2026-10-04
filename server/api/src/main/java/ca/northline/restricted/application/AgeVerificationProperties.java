package ca.northline.restricted.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.age-verification.*}: the identity provider ({@code AGE_VERIFICATION_PROVIDER}: {@code local |
 * stripe}) and where it sends the customer back — the consumer site's cart, or the consumer app (its scheme).
 */
@ConfigurationProperties("northline.age-verification")
public record AgeVerificationProperties(
        @Nullable String provider,
        @DefaultValue("http://localhost:3000/cart?age=done") String webReturnUrl,
        @DefaultValue("ca.northline.app:/age-verified") String appReturnUrl) {

    public String effectiveProvider() {
        return provider == null || provider.isBlank()
                ? "local"
                : provider.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
