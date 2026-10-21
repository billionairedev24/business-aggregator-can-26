package ca.northline.payments.application;

import java.time.Instant;
import java.util.Optional;

/** Outbound port (S-156): {@code payments.appeal_compensations}, one row per overturned dispute appeal. */
public interface AppealCompensationStore {

    Optional<Long> cents(String appealId);

    /** False when the appeal (or the dispute) was already compensated. */
    boolean insert(String appealId, String disputeId, String merchantId, long cents, String by, Instant at);

    void transfer(String appealId, String stripeTransfer);

    /** Whether an applied agent decision (S-80) closed the dispute. */
    boolean agentDecided(String disputeId);
}
