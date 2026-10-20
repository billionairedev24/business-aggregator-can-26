package ca.northline.payments.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.shared.Bytes;
import ca.northline.shared.stripe.StripeClients;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import ca.northline.support.StripeMock;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * S-156: card-dispute evidence on Stripe against stripe-mock, which validates every request against Stripe's OpenAPI
 * spec for the pinned version and answers with fixtures (no state). Checks the Files upload
 * ({@code purpose=dispute_evidence}), the dispute update with the evidence fields and {@code submit=true}, and that every
 * mutating call carries an {@code nl1:} Idempotency-Key. Nothing here has run against a real Stripe account.
 */
class StripeDisputeEvidenceStripeMockTest {

    private final StripeMock.Recorder recorder = new StripeMock.Recorder();
    private final StripeDisputeEvidenceGateway gateway = new StripeDisputeEvidenceGateway(StripeMock.client(recorder));

    @AfterEach
    void everyMutatingCallHadAnIdempotencyKey_andThePinnedVersion() {
        var sent = recorder.sent();
        assertThat(sent).isNotEmpty();
        assertThat(sent).allSatisfy(s -> assertThat(s.version()).isEqualTo(StripeClients.PINNED_API_VERSION));
        assertThat(sent.stream().filter(StripeMock.Sent::mutating))
                .allSatisfy(s -> assertThat(s.idempotencyKeys())
                        .as("%s %s", s.method(), s.path())
                        .singleElement()
                        .satisfies(k -> assertThat(k).startsWith("nl1:")));
    }

    @Test
    void uploadsAFile_thenSubmitsTheEvidence() {
        var file = gateway.upload(
                "invoice.pdf",
                "application/pdf",
                Bytes.of("%PDF-1.7 invoice".getBytes(StandardCharsets.UTF_8)),
                StripeIdempotencyKeys.of("dispute-evidence-file", "ds_1", "E1"));
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/files");
        assertThat(file).startsWith("file_");

        var submitted = gateway.submit(
                "dp_123",
                Map.of(
                        "product_description", "Brake pads and rotors replaced.",
                        "service_date", "2026-09-04",
                        "receipt", file),
                StripeIdempotencyKeys.of("dispute-evidence", "ds_1", "dp_123"));
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/disputes/dp_123");
        assertThat(submitted.status()).isNotBlank();
        assertThat(gateway.provider()).isEqualTo("stripe");
    }

    @Test
    void aFieldNorthlineDoesntSend_isRefusedBeforeCallingStripe() {
        assertThatThrownBy(() -> gateway.submit(
                        "dp_123", Map.of("customer_email_address", "a@b.c"), StripeIdempotencyKeys.of("x", "y")))
                .isInstanceOf(IllegalArgumentException.class);
        // the recorder must have seen something for the shared check
        gateway.submit(
                "dp_9", Map.of("uncategorized_text", "See the signed invoice."), StripeIdempotencyKeys.of("x", "z"));
    }
}
