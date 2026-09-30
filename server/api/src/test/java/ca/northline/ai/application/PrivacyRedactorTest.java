package ca.northline.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PrivacyRedactorTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Card 4242 4242 4242 4242 please|Card [card number] please",
                "card 4000-0566-5566-5556|card [card number]",
                "My SIN is 046 454 286|My SIN is [SIN]",
                "SIN 046454286.|SIN [SIN].",
                "Deposit to 12345-001-1234567|Deposit to [bank account]",
                "bank account number: 0012345678|bank account number: [bank account]",
                "IBAN GB82 WEST 1234 5698 7654 32|IBAN [bank account]",
                "Email me at dana.k@example.com|Email me at [email]",
                "Call 403-555-0199 or (587) 555 0100|Call [phone] or [phone]",
                "Text me +1 403 555 0199|Text me [phone]",
                "key sk-or-v1-abcdef0123456789abcdef|key [secret]"
            })
    void masksWhatMustNotLeave(String in, String out) {
        assertThat(PrivacyRedactor.redact(in)).isEqualTo(out);
    }

    @Test
    void leavesOrdinaryFiguresAlone() {
        var text = "Order NL-48213: 3 items, $1,234.56, due 2026-10-02 at 9:00; job BK-7712; 123 456 789 invalid SIN";
        assertThat(PrivacyRedactor.redact(text)).isEqualTo(text);
    }

    @Test
    void luhn() {
        assertThat(PrivacyRedactor.luhn("4242424242424242")).isTrue();
        assertThat(PrivacyRedactor.luhn("4242424242424241")).isFalse();
    }
}
