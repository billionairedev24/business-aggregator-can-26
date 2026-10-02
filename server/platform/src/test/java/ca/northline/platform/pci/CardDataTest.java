package ca.northline.platform.pci;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** S-110: what counts as cardholder data, and what must not (ids, phone numbers, barcodes, amounts). */
class CardDataTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "4242424242424242", // Stripe's test Visa
                "4242 4242 4242 4242",
                "4000-0566-5566-5556",
                "5555 5555 5555 4444", // Mastercard
                "2223003122003222", // Mastercard 2-series
                "378282246310005", // American Express
                "6011111111111117", // Discover
                "3566002020360505", // JCB
                "36227206271667", // Diners (14)
                "6200000000000005", // UnionPay
                "pay with 4242424242424242 please"
            })
    void pans(String text) {
        assertThat(CardData.containsPan(text)).as(text).isTrue();
        assertThat(CardData.findPans(text).getFirst().masked()).endsWith("]").startsWith("[CARD …");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "4242424242424241", // not Luhn-valid
                "1234567890123", // Luhn-invalid, no brand
                "9999999999999995", // Luhn-valid, no card brand
                "+4915112345674", // E.164 phone number
                "01J9ZD3V00000000000000PWM1", // ULID
                "pm_4242424242424242", // glued to an id
                "424242424242", // 12 digits
                "81437 cents",
                "2026-09-30T15:00:00Z"
            })
    void notPans(String text) {
        assertThat(CardData.containsPan(text)).as(text).isFalse();
    }

    @Test
    void maskKeepsTheLastFour() {
        assertThat(CardData.mask("4242 4242 4242 4242")).isEqualTo("[CARD …4242]");
        assertThat(CardData.findPans("a 5555-5555-5555-4444 b")).singleElement().satisfies(p -> {
            assertThat(p.digits()).isEqualTo("5555555555554444");
            assertThat(p.start()).isEqualTo(2);
        });
    }

    @Test
    void trackDataAndVerificationCodes() {
        assertThat(CardData.containsTrackData("%B4242424242424242^DOE/JANE^2912101000000000000?"))
                .isTrue();
        assertThat(CardData.containsTrackData(";4242424242424242=29121010000000000000?"))
                .isTrue();
        assertThat(CardData.containsTrackData("a=b;c=d")).isFalse();
        assertThat(CardData.containsVerificationCode("{\"cvc\":\"123\"}")).isTrue();
        assertThat(CardData.containsVerificationCode("CVV2: 1234")).isTrue();
        assertThat(CardData.containsVerificationCode("security code 987")).isTrue();
        assertThat(CardData.containsVerificationCode("cvc check passed")).isFalse();
        assertThat(CardData.containsCardData("nothing here, order NL-50012")).isFalse();
    }

    @Test
    void cardDataNames() {
        for (var name : new String[] {
            "cardNumber",
            "card_number",
            "pan",
            "PAN",
            "primaryAccountNumber",
            "cvc",
            "cvv2",
            "card-security-code",
            "track2",
            "track_data",
            "pinBlock",
            "card.number"
        }) {
            assertThat(CardData.isCardDataName(name)).as(name).isTrue();
        }
        for (var name : new String[] {
            "last4",
            "cardLast4",
            "brand",
            "exp_month",
            "expYear",
            "span",
            "company",
            "spanId",
            "panel",
            "trackingNumber",
            "card_brand",
            "payment_method",
            "stripe_card",
            "cardholder_name_hash"
        }) {
            assertThat(CardData.isCardDataName(name)).as(name).isFalse();
        }
    }
}
