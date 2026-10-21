package ca.northline.payments.application;

import ca.northline.shared.Bytes;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-156): card-dispute evidence to the card network through Stripe. Selected by
 * {@code northline.payments.dispute-evidence.provider} ({@code DISPUTE_EVIDENCE_PROVIDER}): {@code stripe} (the Files and
 * Disputes APIs with the payments key) or {@code local} (an in-process fake; refused under staging and prod). Every
 * call carries an idempotency key so a retried submission sends nothing twice.
 */
public interface DisputeEvidenceGateway {

    String provider();

    /** Uploads one file with {@code purpose=dispute_evidence}; returns Stripe's {@code file_…} id. */
    String upload(String name, String contentType, Bytes bytes, String idempotencyKey);

    /**
     * Sets the dispute's evidence (Stripe's field names → text or {@code file_…} ids) and submits it ({@code submit=true}:
     * no more changes). Returns Stripe's dispute status and deadline afterwards.
     */
    Submitted submit(String stripeDispute, Map<String, String> evidence, String idempotencyKey);

    record Submitted(String status, @Nullable Instant dueBy) {}
}
