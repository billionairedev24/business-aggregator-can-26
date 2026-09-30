package ca.northline.payments.infra;

import ca.northline.shared.stripe.StripeClients;
import java.time.Clock;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the Stripe adapter: stripe-java when {@code northline.payments.stripe-secret-key} is set (env
 * {@code STRIPE_SECRET_KEY}; with {@code STRIPE_API_BASE} it talks to stripe-mock), the local fake otherwise — so
 * local, test and CI never call Stripe unless asked to. Staging and prod require the key (S-1).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentsProperties.class)
class PaymentsGatewayConfig {

    private static final String HAS_KEY = "'${northline.payments.stripe-secret-key:}' != ''";
    private static final String NO_KEY = "'${northline.payments.stripe-secret-key:}' == ''";

    @Bean
    @ConditionalOnExpression(HAS_KEY)
    StripeConnectGateway stripeConnectGateway(PaymentsProperties props) {
        var base = props.stripeApiBase();
        if (base != null && !base.isBlank()) {
            log.info("Payments: Stripe API base overridden to {} (stripe-mock)", base);
        }
        return new StripeConnectGateway(
                StripeClients.create(Objects.requireNonNull(props.stripeSecretKey()), base),
                props.stripePublishableKey());
    }

    @Bean
    @ConditionalOnExpression(NO_KEY)
    FakeStripeGateway fakeStripeGateway(Clock clock) {
        log.info("Payments: no Stripe key configured — using the fake Stripe gateway (nothing leaves this process).");
        return new FakeStripeGateway(clock);
    }
}
