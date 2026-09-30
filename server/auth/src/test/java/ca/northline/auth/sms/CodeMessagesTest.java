package ca.northline.auth.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Templates: language by locale, one GSM-7 segment per SMS, digits read one by one. */
class CodeMessagesTest {

    /** GSM 03.38 basic character set (no escape table characters). */
    private static final String GSM7 = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    @Test
    void frenchForFrenchLocales_englishOtherwise() {
        assertThat(CodeMessages.of(Locale.CANADA_FRENCH)).isEqualTo(CodeMessages.FR);
        assertThat(CodeMessages.of(Locale.FRENCH)).isEqualTo(CodeMessages.FR);
        assertThat(CodeMessages.of(Locale.CANADA)).isEqualTo(CodeMessages.EN);
        assertThat(CodeMessages.of(Locale.forLanguageTag("pa-CA"))).isEqualTo(CodeMessages.EN);
    }

    @Test
    void everySmsIsOneGsm7Segment() {
        for (var messages : CodeMessages.values()) {
            var sms = messages.sms("123456");
            assertThat(sms).hasSizeLessThanOrEqualTo(160).contains("123456").startsWith("Northline");
            assertThat(sms.chars()).allMatch(c -> GSM7.indexOf(c) >= 0);
        }
    }

    @Test
    void voiceReadsEachDigit() {
        assertThat(CodeMessages.EN.voice("040506")).contains("0, 4, 0, 5, 0, 6. Again");
        assertThat(CodeMessages.FR.voice("040506")).contains("0, 4, 0, 5, 0, 6. Je répète");
        assertThat(CodeMessages.FR.languageTag()).isEqualTo("fr-CA");
    }
}
