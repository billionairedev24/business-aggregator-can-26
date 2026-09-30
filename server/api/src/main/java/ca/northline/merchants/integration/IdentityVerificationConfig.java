package ca.northline.merchants.integration;

import ca.northline.merchants.application.IdentityVerification;
import ca.northline.merchants.application.OwnerIdentity.ApplyIdentitySession;
import ca.northline.shared.stripe.StripeClients;
import java.time.Clock;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Picks the {@link IdentityVerification} adapter by {@code northline.identity.provider} ({@code IDENTITY_PROVIDER}):
 * {@code local} (default: the fake with the outcome page; refused under {@code staging}/{@code prod}, a warning under
 * {@code dev}) or {@code stripe} (stripe-java with {@code STRIPE_SECRET_KEY}; {@code STRIPE_API_BASE} points it at
 * stripe-mock). The fake is also the {@code DevIdentityOutcomes} bean behind the local outcome page (Spring matches it by
 * its runtime type). docs/runbooks/stripe.md § Identity.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityVerificationConfig.IdentityProperties.class)
class IdentityVerificationConfig {

    /** @param provider {@code local | stripe} */
    @ConfigurationProperties("northline.identity")
    record IdentityProperties(@Nullable String provider) {
        String effective() {
            return provider == null || provider.isBlank()
                    ? "local"
                    : provider.strip().toLowerCase(Locale.ROOT);
        }
    }

    @Bean
    IdentityVerification identityVerification(
            IdentityProperties properties,
            StripeConnectAccountGateway.Properties stripe,
            Environment environment,
            @Value("${northline.notifications.api-url:http://localhost:8080}") String apiUrl,
            ObjectProvider<ApplyIdentitySession> apply,
            Clock clock) {
        var provider = properties.effective();
        log.info("Identity verification provider: {}", provider);
        return switch (provider) {
            case "stripe" -> {
                var key = stripe.secretKey();
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException(
                            "IDENTITY_PROVIDER=stripe needs STRIPE_SECRET_KEY (docs/runbooks/stripe.md § Identity)");
                }
                yield new StripeIdentityVerification(StripeClients.create(key, stripe.apiBase()));
            }
            case "local" -> {
                if (environment.acceptsProfiles(Profiles.of("staging", "prod"))) {
                    throw new IllegalStateException(
                            "IDENTITY_PROVIDER=local is refused under staging/prod: set IDENTITY_PROVIDER=stripe");
                }
                if (environment.acceptsProfiles(Profiles.of("dev"))) {
                    log.warn("IDENTITY_PROVIDER=local under dev: owners can't finish identity verification here");
                }
                yield new FakeIdentityVerification(apiUrl, apply, clock);
            }
            default ->
                throw new IllegalStateException(
                        "Unknown IDENTITY_PROVIDER '" + provider + "' (expected local or stripe)");
        };
    }
}
