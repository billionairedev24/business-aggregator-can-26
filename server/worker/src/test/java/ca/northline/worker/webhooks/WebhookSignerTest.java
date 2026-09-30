package ca.northline.worker.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** The Northline-Signature header (Stripe's scheme) and the reference verification receivers follow. */
class WebhookSignerTest {

    static final String SECRET = "whsec_test_not_a_real_secret_0001";
    static final String OLD = "whsec_test_not_a_real_secret_0000";
    static final Instant AT = Instant.parse("2026-09-30T18:00:00Z");
    static final byte[] BODY =
            "{\"id\":\"01J9ZD3V000000000000000EV1\",\"type\":\"booking.completed\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void signsTimestampDotBodyWithTheWholeSecret() throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((AT.getEpochSecond() + ".").getBytes(StandardCharsets.UTF_8));
        var expected = HexFormat.of().formatHex(mac.doFinal(BODY));

        assertThat(WebhookSigner.header(AT, BODY, List.of(SECRET)))
                .isEqualTo("t=" + AT.getEpochSecond() + ",v1=" + expected);
    }

    @Test
    void duringARotationBothSecretsSign_andEitherVerifies() {
        var header = WebhookSigner.header(AT, BODY, List.of(SECRET, OLD));

        assertThat(header.split(",v1=")).hasSize(3);
        assertThat(WebhookSigner.verify(header, BODY, SECRET, AT, WebhookSigner.TOLERANCE))
                .isTrue();
        assertThat(WebhookSigner.verify(header, BODY, OLD, AT, WebhookSigner.TOLERANCE))
                .isTrue();
        assertThat(WebhookSigner.verify(header, BODY, "whsec_test_someone_else", AT, WebhookSigner.TOLERANCE))
                .isFalse();
    }

    @Test
    void aChangedBody_anOldTimestampOrAMissingPartFailsVerification() {
        var header = WebhookSigner.header(AT, BODY, List.of(SECRET));
        var tampered =
                "{\"id\":\"01J9ZD3V000000000000000EV1\",\"type\":\"refund.issued\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(WebhookSigner.verify(header, tampered, SECRET, AT, WebhookSigner.TOLERANCE))
                .isFalse();
        assertThat(WebhookSigner.verify(header, BODY, SECRET, AT.plus(Duration.ofMinutes(6)), WebhookSigner.TOLERANCE))
                .isFalse();
        assertThat(WebhookSigner.verify(
                        header.substring(header.indexOf(',') + 1), BODY, SECRET, AT, Duration.ofMinutes(5)))
                .isFalse();
        assertThatThrownBy(() -> WebhookSigner.header(AT, BODY, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
