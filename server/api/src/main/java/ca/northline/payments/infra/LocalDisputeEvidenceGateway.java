package ca.northline.payments.infra;

import ca.northline.payments.application.DisputeEvidenceGateway;
import ca.northline.shared.Bytes;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The local stand-in for Stripe's dispute evidence (S-156, {@code DISPUTE_EVIDENCE_PROVIDER=local}): nothing leaves the
 * process. A file gets a stable {@code file_local_…} id per idempotency key; a submission answers
 * {@code under_review}, as Stripe does once evidence is submitted. A dispute id containing {@code rejectEvidence} is
 * refused, so the console's error path can be tried by hand.
 */
class LocalDisputeEvidenceGateway implements DisputeEvidenceGateway {

    private final Clock clock;
    private final Map<String, String> files = new ConcurrentHashMap<>();

    LocalDisputeEvidenceGateway(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public String upload(String name, String contentType, Bytes bytes, String idempotencyKey) {
        if (bytes.isEmpty()) {
            throw new IllegalArgumentException("empty evidence file");
        }
        return files.computeIfAbsent(idempotencyKey, k -> "file_local_" + Integer.toHexString(k.hashCode()));
    }

    @Override
    public Submitted submit(String stripeDispute, Map<String, String> evidence, String idempotencyKey) {
        if (stripeDispute.contains("rejectEvidence")) {
            throw new IllegalStateException("the dispute is not accepting evidence (local fake)");
        }
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("no evidence");
        }
        return new Submitted("under_review", clock.instant().plus(java.time.Duration.ofDays(7)));
    }
}
