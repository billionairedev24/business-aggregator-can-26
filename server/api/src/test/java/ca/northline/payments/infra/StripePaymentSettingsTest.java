package ca.northline.payments.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.payments.api.PaymentSettings.Wallets;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** S-157: wallets follow the gateway switch — only Stripe with a publishable key offers them, each when switched on. */
class StripePaymentSettingsTest {

    private static StripePaymentSettings settings(
            @Nullable String secret, @Nullable String publishable, boolean applePay, boolean googlePay) {
        return new StripePaymentSettings(new PaymentsProperties(
                secret, publishable, null, null, null, null, null, null, applePay, googlePay));
    }

    @Test
    void walletsOnlyUnderStripe() {
        assertThat(settings("sk_test_fake", "pk_test_fake", true, false).wallets())
                .isEqualTo(new Wallets(true, false));
        assertThat(settings("sk_test_fake", "pk_test_fake", true, true).wallets())
                .isEqualTo(new Wallets(true, true));
        assertThat(settings(null, null, true, true).wallets()).isEqualTo(Wallets.NONE);
        assertThat(settings("sk_test_fake", null, true, true).wallets()).isEqualTo(Wallets.NONE);
        assertThat(settings("sk_test_fake", "pk_test_fake", false, false).wallets())
                .isEqualTo(Wallets.NONE);
    }
}
