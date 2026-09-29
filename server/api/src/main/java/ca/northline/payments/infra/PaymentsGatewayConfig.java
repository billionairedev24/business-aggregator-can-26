package ca.northline.payments.infra;

import com.stripe.StripeClient;
import java.time.Clock;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the Stripe adapter: stripe-java when {@code northline.payments.stripe-secret-key} is set (env
 * {@code NORTHLINE_PAYMENTS_STRIPE_SECRET_KEY}), the local fake otherwise — so local, test and CI never call Stripe.
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
        var client = StripeClient.builder().setApiKey(Objects.requireNonNull(props.stripeSecretKey()));
        var base = props.stripeApiBase();
        if (base != null && !base.isBlank()) {
            client.setApiBase(base).setConnectBase(base).setFilesBase(base);
        }
        return new StripeConnectGateway(client.build(), props.stripePublishableKey());
    }

    @Bean
    @ConditionalOnExpression(NO_KEY)
    FakeStripeGateway fakeStripeGateway(Clock clock) {
        log.info("Payments: no Stripe key configured — using the fake Stripe gateway (nothing leaves this process).");
        return new FakeStripeGateway(clock);
    }
}
