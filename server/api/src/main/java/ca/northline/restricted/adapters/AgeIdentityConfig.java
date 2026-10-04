package ca.northline.restricted.adapters;

import ca.northline.restricted.application.AgeIdentityProvider;
import ca.northline.restricted.application.AgeVerificationProperties;
import ca.northline.restricted.application.AgeVerificationUseCases.ApplyAgeSession;
import ca.northline.shared.stripe.StripeClients;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Picks the {@link AgeIdentityProvider} by {@code northline.age-verification.provider} ({@code
 * AGE_VERIFICATION_PROVIDER}): {@code local} (default: the fake with the outcome page; refused under {@code
 * staging}/{@code prod}, a warning under {@code dev}) or {@code stripe} ({@code STRIPE_SECRET_KEY}; {@code
 * STRIPE_API_BASE} points it at stripe-mock). docs/runbooks/age-restricted.md § Stripe Identity.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgeVerificationProperties.class)
class AgeIdentityConfig {

    @Bean
    AgeIdentityProvider ageIdentityProvider(
            AgeVerificationProperties properties,
            Environment environment,
            @Value("${northline.stripe.secret-key:}") @Nullable String secretKey,
            @Value("${northline.stripe.api-base:}") @Nullable String apiBase,
            @Value("${northline.notifications.api-url:http://localhost:8080}") String apiUrl,
            ObjectProvider<ApplyAgeSession> apply) {
        var provider = properties.effectiveProvider();
        log.info("Age verification provider: {}", provider);
        return switch (provider) {
            case "stripe" -> {
                if (secretKey == null || secretKey.isBlank()) {
                    throw new IllegalStateException(
                            "AGE_VERIFICATION_PROVIDER=stripe needs STRIPE_SECRET_KEY (docs/runbooks/age-restricted.md)");
                }
                yield new StripeAgeIdentity(
                        StripeClients.create(secretKey, apiBase == null || apiBase.isBlank() ? null : apiBase));
            }
            case "local" -> {
                if (environment.acceptsProfiles(Profiles.of("staging", "prod"))) {
                    throw new IllegalStateException(
                            "AGE_VERIFICATION_PROVIDER=local is refused under staging/prod: set it to stripe");
                }
                if (environment.acceptsProfiles(Profiles.of("dev"))) {
                    log.warn("AGE_VERIFICATION_PROVIDER=local under dev: customers' age checks use the fake");
                }
                yield new FakeAgeIdentity(apiUrl, apply);
            }
            default ->
                throw new IllegalStateException(
                        "Unknown AGE_VERIFICATION_PROVIDER '" + provider + "' (expected local or stripe)");
        };
    }
}
