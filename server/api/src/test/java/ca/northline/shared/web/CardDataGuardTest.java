package ca.northline.shared.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** S-110: what the request guard refuses, where it says it is, and what it lets through. */
class CardDataGuardTest {

    private final CardDataGuard guard = new CardDataGuard(JsonMapper.builder().build());

    private CardDataGuard.Found found(String json) {
        return guard.findCardData(json.getBytes(UTF_8)).orElseThrow(() -> new AssertionError("nothing in " + json));
    }

    private boolean clean(String json) {
        return guard.findCardData(json.getBytes(UTF_8)).isEmpty();
    }

    @Test
    void aCardNumberInAnyStringIsFoundWithItsPath() {
        assertThat(found("{\"note\":\"my card is 4242 4242 4242 4242 exp 12/29\"}"))
                .isEqualTo(new CardDataGuard.Found("note", "[CARD …4242]"));
        assertThat(found("{\"lines\":[{\"label\":\"x\"},{\"label\":\"5555-5555-5555-4444\"}]}")
                        .field())
                .isEqualTo("lines[1].label");
        assertThat(found("{\"messages\":[\"hi\",\"378282246310005\"]}").field()).isEqualTo("messages[1]");
        assertThat(found("{\"amount\":4242424242424242}").field()).isEqualTo("amount");
        assertThat(found("\"4242424242424242\"").field()).isEqualTo("body");
    }

    @Test
    void cardFieldsTrackDataAndCodes() {
        assertThat(found("{\"payment\":{\"cardNumber\":\"x\"}}").field()).isEqualTo("payment.cardNumber");
        assertThat(found("{\"cvc\":\"123\"}").field()).isEqualTo("cvc");
        assertThat(found("{\"text\":\"cvv: 123\"}").what()).isEqualTo("a card verification code");
        assertThat(found("{\"text\":\";4242424242424242=29121010000000000000?\"}")
                        .field())
                .isEqualTo("text");
    }

    @Test
    void idsPhonesBarcodesAndAmountsPass() {
        assertThat(clean("{\"gtin\":\"4006381333924\",\"sku\":\"4242424242424242\"}"))
                .isTrue();
        assertThat(clean("{\"phone\":\"+4915112345674\",\"to\":\"+1 587 555 0101\"}"))
                .isTrue();
        assertThat(clean("{\"paymentMethodId\":\"pm_4242424242424242\",\"id\":\"01J9ZD3V00000000000000PWM1\"}"))
                .isTrue();
        assertThat(clean("{\"amountCents\":81437,\"at\":\"2026-09-30T15:00:00Z\",\"cardLast4\":\"4242\"}"))
                .isTrue();
        assertThat(clean("{\"cvc\":\"\",\"pan\":null}")).isTrue();
        assertThat(clean("not json {")).isTrue();
        assertThat(clean("")).isTrue();
    }
}
