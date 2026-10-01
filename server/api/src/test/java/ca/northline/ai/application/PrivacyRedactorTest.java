package ca.northline.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The AI layer's own rules (SIN, bank accounts) plus the logging Redactor's (S-112) it reuses. */
class PrivacyRedactorTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "My SIN is 046 454 286|My SIN is [SIN]",
                "SIN 046454286.|SIN [SIN].",
                "Deposit to 12345-001-1234567|Deposit to [BANK ACCOUNT]",
                "bank account number: 0012345678|bank account number: [BANK ACCOUNT]",
                "IBAN GB82 WEST 1234 5698 7654 32|IBAN [BANK ACCOUNT]",
                "Card 4242 4242 4242 4242 please|Card [CARD …4242] please",
                "Email me at dana.k@example.com|Email me at [EMAIL]",
                "Call 403-555-0199 or (587) 555 0100|Call [PHONE] or [PHONE]",
                "key sk-or-v1-abcdef0123456789abcdef|key sk-[REDACTED]"
            })
    void masksWhatMustNotLeave(String in, String out) {
        assertThat(PrivacyRedactor.redact(in)).isEqualTo(out);
    }

    @Test
    void leavesOrdinaryFiguresAlone() {
        var text = "Order NL-48213: 3 items, $1,234.56, due 2026-10-02 at 9:00; job BK-7712; 123 456 789 is no SIN";
        assertThat(PrivacyRedactor.redact(text)).isEqualTo(text);
    }

    @Test
    void luhn() {
        assertThat(PrivacyRedactor.luhn("046454286")).isTrue();
        assertThat(PrivacyRedactor.luhn("123456789")).isFalse();
    }
}
