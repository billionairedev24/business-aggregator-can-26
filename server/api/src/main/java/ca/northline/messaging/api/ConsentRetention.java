package ca.northline.messaging.api;

import java.time.Instant;
import java.time.Period;

/**
 * How long the CASL proof of consent is kept (S-108), exposed for the data-retention jobs (S-107) and the retention
 * report: {@code messaging.consent_records} of a category stay while the consent is active and for
 * {@link #PROOF_PERIOD} after it was withdrawn — by the person, an unsubscribe link, staff or the account's erasure —
 * then they go. Express consent itself doesn't expire under CASL, so an active consent's records are never purged.
 * The messaging module runs {@link #purgeExpiredProofs} daily itself; calling it again is harmless (idempotent).
 */
public interface ConsentRetention {

    /** CASL s. 13 puts the burden of proving consent on the sender; three years covers the limitation period. */
    Period PROOF_PERIOD = Period.ofYears(3);

    /** The retention schedule's entry: the data and its period. */
    default Period proofPeriod() {
        return PROOF_PERIOD;
    }

    /** Deletes the records of every consent withdrawn more than {@link #proofPeriod()} before {@code now}. */
    int purgeExpiredProofs(Instant now);
}
