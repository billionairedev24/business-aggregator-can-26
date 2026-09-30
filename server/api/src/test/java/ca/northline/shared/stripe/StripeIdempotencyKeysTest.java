package ca.northline.shared.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stripe.Stripe;
import org.junit.jupiter.api.Test;

class StripeIdempotencyKeysTest {

    @Test
    void domainKeys_nameTheOperationAndTheIds() {
        assertThat(StripeIdempotencyKeys.of("capture", "01J9ZD3V00000000000000ESC1", "01J9ZD3V00000000000000PI01"))
                .isEqualTo("nl1:capture:01J9ZD3V00000000000000ESC1:01J9ZD3V00000000000000PI01");
        assertThat(StripeIdempotencyKeys.of("scheduled-payout", "01J9ZD3V00000000000000PWM1", "2026-10-02"))
                .isEqualTo("nl1:scheduled-payout:01J9ZD3V00000000000000PWM1:2026-10-02");
        assertThat(StripeIdempotencyKeys.of("manual-payouts", "acct_1Kx9Q2"))
                .isEqualTo("nl1:manual-payouts:acct_1Kx9Q2");
    }

    @Test
    void domainKeys_areDeterministic_andDifferPerOperationAndId() {
        assertThat(StripeIdempotencyKeys.of("transfer", "E1")).isEqualTo(StripeIdempotencyKeys.of("transfer", "E1"));
        assertThat(StripeIdempotencyKeys.of("transfer", "E1")).isNotEqualTo(StripeIdempotencyKeys.of("transfer", "E2"));
        assertThat(StripeIdempotencyKeys.of("transfer", "E1")).isNotEqualTo(StripeIdempotencyKeys.of("refund", "E1"));
    }

    @Test
    void separatorsInIdsAreRefused_soTwoKeysCanNeverCollide() {
        assertThatThrownBy(() -> StripeIdempotencyKeys.of("transfer", "a:b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StripeIdempotencyKeys.of("transfer", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StripeIdempotencyKeys.of("trans fer")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clientKeys_sameScopeAndKey_sameStripeKey_otherwiseDifferent_andTheRawKeyNeverLeaves() {
        var key = StripeIdempotencyKeys.fromClient("instant-payout", "PWM1:RAV1", "3f1c2c1e-client-key");
        assertThat(key)
                .isEqualTo(StripeIdempotencyKeys.fromClient("instant-payout", "PWM1:RAV1", "3f1c2c1e-client-key"));
        assertThat(key)
                .startsWith("nl1:instant-payout:")
                .doesNotContain("3f1c2c1e")
                .hasSizeLessThanOrEqualTo(255);
        assertThat(key)
                .isNotEqualTo(StripeIdempotencyKeys.fromClient("instant-payout", "PWM1:JAS1", "3f1c2c1e-client-key"));
        assertThat(key).isNotEqualTo(StripeIdempotencyKeys.fromClient("instant-payout", "PWM1:RAV1", "another-key"));
        assertThat(key).isNotEqualTo(StripeIdempotencyKeys.fromClient("authorize", "PWM1:RAV1", "3f1c2c1e-client-key"));
        assertThatThrownBy(() -> StripeIdempotencyKeys.fromClient("instant-payout", "s", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overLongKeys_areShortenedToStripesLimit_withoutLosingUniqueness() {
        var a = StripeIdempotencyKeys.of("op", "a".repeat(300));
        var b = StripeIdempotencyKeys.of("op", "a".repeat(299) + "b");
        assertThat(a).hasSize(255).isNotEqualTo(b);
        assertThat(b).hasSize(255);
    }

    @Test
    void apiVersion_isPinned_toTheOneStripeJavaSpeaks() {
        assertThat(Stripe.API_VERSION).isEqualTo(StripeClients.PINNED_API_VERSION);
        assertThatThrownBy(() -> StripeClients.requirePinnedVersion("2020-08-27"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(StripeClients.PINNED_API_VERSION);
        assertThatThrownBy(() -> StripeClients.create(" ", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
