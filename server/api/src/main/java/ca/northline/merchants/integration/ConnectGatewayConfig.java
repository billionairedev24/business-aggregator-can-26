package ca.northline.merchants.integration;

import ca.northline.merchants.application.ConnectAccountGateway;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Picks the {@link ConnectAccountGateway}: stripe-java whenever {@code northline.stripe.secret-key}
 * ({@code STRIPE_SECRET_KEY}) is set — under {@code local} too, so {@code STRIPE_API_BASE} + stripe-mock exercises the
 * real adapter — and outside {@code local}/{@code test} always (answering 409 {@code stripe_unavailable} without a
 * key); the design's fake under {@code local}/{@code test} without a key.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StripeConnectAccountGateway.Properties.class)
class ConnectGatewayConfig {

    private static final Profiles FAKE_PROFILES = Profiles.of("local", "test");

    static final class UseStripe implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            return !env.getProperty("northline.stripe.secret-key", "").isBlank() || !env.acceptsProfiles(FAKE_PROFILES);
        }
    }

    static final class UseFake implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !new UseStripe().matches(context, metadata);
        }
    }

    @Bean
    @Conditional(UseStripe.class)
    StripeConnectAccountGateway stripeConnectAccountGateway(StripeConnectAccountGateway.Properties properties) {
        return new StripeConnectAccountGateway(properties);
    }

    @Bean
    @Conditional(UseFake.class)
    FakeConnectAccountGateway fakeConnectAccountGateway(Clock clock) {
        return new FakeConnectAccountGateway(clock);
    }
}
