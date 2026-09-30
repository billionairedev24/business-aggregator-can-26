package ca.northline.payments.infra;

import ca.northline.payments.api.PaymentSettings;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** {@link PaymentSettings} from the same switch as the gateway: a secret key means Stripe, none the local fake. */
@Component
class StripePaymentSettings implements PaymentSettings {

    private final PaymentsProperties properties;

    StripePaymentSettings(PaymentsProperties properties) {
        this.properties = properties;
    }

    @Override
    public String provider() {
        var key = properties.stripeSecretKey();
        return key == null || key.isBlank() ? "fake" : "stripe";
    }

    @Override
    public @Nullable String publishableKey() {
        var key = properties.stripePublishableKey();
        return "stripe".equals(provider()) && key != null && !key.isBlank() ? key : null;
    }
}
