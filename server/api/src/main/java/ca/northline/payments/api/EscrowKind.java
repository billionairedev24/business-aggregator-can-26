package ca.northline.payments.api;

import ca.northline.shared.CodedEnum;
import java.time.Duration;
import java.time.Instant;

/**
 * What the money pays for, which decides when escrow releases (CLAUDE.md non-negotiables): services 48 h after
 * completion, goods 7 days after delivery, food on handoff. A customer sign-off / delivery confirmation releases at once.
 */
public enum EscrowKind implements CodedEnum {
    SERVICE(Duration.ofDays(2)),
    GOODS(Duration.ofDays(7)),
    FOOD(Duration.ZERO);

    private final Duration releaseAfterFulfilment;

    EscrowKind(Duration releaseAfterFulfilment) {
        this.releaseAfterFulfilment = releaseAfterFulfilment;
    }

    /** When money held for work fulfilled at {@code fulfilledAt} releases automatically. */
    public Instant releaseAt(Instant fulfilledAt) {
        return fulfilledAt.plus(releaseAfterFulfilment);
    }
}
