package ca.northline.payments.infra;

import ca.northline.payments.application.DisputeEvidenceGateway;
import ca.northline.shared.Bytes;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.DisputeUpdateParams;
import com.stripe.param.FileCreateParams;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Card-dispute evidence through stripe-java (S-156): each file is uploaded with {@code purpose=dispute_evidence} (Files
 * API), then the dispute's {@code evidence} is set with {@code submit=true} in one update — Stripe sends it to the
 * card network and the dispute moves to {@code under_review}. Both calls carry Northline's idempotency key. Never run
 * against the live API in this repository — tested against stripe-mock ({@code StripeDisputeEvidenceStripeMockTest}).
 */
class StripeDisputeEvidenceGateway implements DisputeEvidenceGateway {

    /** Stripe's evidence field → its builder setter (the fields {@code CardDisputes} lets agents fill). */
    private static final Map<String, BiConsumer<DisputeUpdateParams.Evidence.Builder, String>> FIELDS = Map.ofEntries(
            Map.entry("product_description", DisputeUpdateParams.Evidence.Builder::setProductDescription),
            Map.entry("service_date", DisputeUpdateParams.Evidence.Builder::setServiceDate),
            Map.entry(
                    "cancellation_policy_disclosure",
                    DisputeUpdateParams.Evidence.Builder::setCancellationPolicyDisclosure),
            Map.entry("cancellation_rebuttal", DisputeUpdateParams.Evidence.Builder::setCancellationRebuttal),
            Map.entry("refund_policy_disclosure", DisputeUpdateParams.Evidence.Builder::setRefundPolicyDisclosure),
            Map.entry("refund_refusal_explanation", DisputeUpdateParams.Evidence.Builder::setRefundRefusalExplanation),
            Map.entry("access_activity_log", DisputeUpdateParams.Evidence.Builder::setAccessActivityLog),
            Map.entry("uncategorized_text", DisputeUpdateParams.Evidence.Builder::setUncategorizedText),
            Map.entry("receipt", DisputeUpdateParams.Evidence.Builder::setReceipt),
            Map.entry("service_documentation", DisputeUpdateParams.Evidence.Builder::setServiceDocumentation),
            Map.entry("customer_communication", DisputeUpdateParams.Evidence.Builder::setCustomerCommunication),
            Map.entry("cancellation_policy", DisputeUpdateParams.Evidence.Builder::setCancellationPolicy),
            Map.entry("refund_policy", DisputeUpdateParams.Evidence.Builder::setRefundPolicy),
            Map.entry("uncategorized_file", DisputeUpdateParams.Evidence.Builder::setUncategorizedFile));

    private final StripeClient stripe;

    StripeDisputeEvidenceGateway(StripeClient stripe) {
        this.stripe = stripe;
    }

    @FunctionalInterface
    private interface StripeCall<T> {
        T run() throws StripeException;
    }

    private static <T> T call(String what, StripeCall<T> call) {
        try {
            return call.run();
        } catch (StripeException e) {
            throw new IllegalStateException("Stripe " + what + " failed: " + e.getMessage(), e);
        }
    }

    private static RequestOptions key(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    @Override
    public String provider() {
        return "stripe";
    }

    @Override
    public String upload(String name, String contentType, Bytes bytes, String idempotencyKey) {
        var params = FileCreateParams.builder()
                .setPurpose(FileCreateParams.Purpose.DISPUTE_EVIDENCE)
                .setFile(new ByteArrayInputStream(bytes.toArray()))
                .build();
        return call("file upload", () -> stripe.v1().files().create(params, key(idempotencyKey)))
                .getId();
    }

    @Override
    public Submitted submit(String stripeDispute, Map<String, String> evidence, String idempotencyKey) {
        var builder = DisputeUpdateParams.Evidence.builder();
        evidence.forEach((field, value) -> {
            var setter = FIELDS.get(field);
            if (setter == null) {
                throw new IllegalArgumentException("Not a Stripe evidence field Northline sends: " + field);
            }
            setter.accept(builder, value);
        });
        var params = DisputeUpdateParams.builder()
                .setEvidence(builder.build())
                .setSubmit(true)
                .putMetadata("northline_evidence", "console")
                .build();
        var dispute =
                call("dispute update", () -> stripe.v1().disputes().update(stripeDispute, params, key(idempotencyKey)));
        var details = dispute.getEvidenceDetails();
        var due = details == null || details.getDueBy() == null ? null : Instant.ofEpochSecond(details.getDueBy());
        return new Submitted(dispute.getStatus() == null ? "under_review" : dispute.getStatus(), due);
    }
}
