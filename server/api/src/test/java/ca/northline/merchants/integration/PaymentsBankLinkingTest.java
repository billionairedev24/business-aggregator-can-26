package ca.northline.merchants.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.merchants.application.VerificationGateways.Outcome;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** S-24: onboarding's bank check outside local/test — verified with the linked account's label, else waiting. */
class PaymentsBankLinkingTest {

    @Test
    void verifiedWithTheLabel_whenABankIsLinked() {
        assertThat(new PaymentsBankLinking(_ -> Optional.of("RBC ··8820")).link("m1"))
                .isEqualTo(new Outcome(true, "RBC ··8820"));
    }

    @Test
    void waitsForTheLink_otherwise() {
        assertThat(new PaymentsBankLinking(_ -> Optional.empty()).link("m1"))
                .isEqualTo(new Outcome(false, PaymentsBankLinking.AWAITING));
    }
}
