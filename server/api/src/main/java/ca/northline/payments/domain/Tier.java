package ca.northline.payments.domain;

import ca.northline.shared.CodedEnum;
import org.jspecify.annotations.Nullable;

/**
 * Merchant tier as far as money is concerned (the merchants module owns the tier itself). Take rates from design 02
 * Earnings: "take rate · Master (Trusted 12%, Registered 15%)" = 9 %. Dispute-rate floors: Master 1 % (design 02
 * Refunds); Trusted and Registered are ours (DECISIONS.md).
 */
public enum Tier implements CodedEnum {
    REGISTERED(1500, 200),
    TRUSTED(1200, 150),
    MASTER(900, 100);

    private final int takeRateBps;
    private final int disputeRateFloorBps;

    Tier(int takeRateBps, int disputeRateFloorBps) {
        this.takeRateBps = takeRateBps;
        this.disputeRateFloorBps = disputeRateFloorBps;
    }

    public int takeRateBps() {
        return takeRateBps;
    }

    public int disputeRateFloorBps() {
        return disputeRateFloorBps;
    }

    /** Applicants and unknown tiers pay the Registered rate. */
    public static Tier of(@Nullable String code) {
        if (code == null) {
            return REGISTERED;
        }
        for (var t : values()) {
            if (t.code().equals(code)) {
                return t;
            }
        }
        return REGISTERED;
    }
}
