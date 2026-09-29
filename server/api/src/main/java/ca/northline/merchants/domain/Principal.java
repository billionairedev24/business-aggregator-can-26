package ca.northline.merchants.domain;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** An owner, partner, director or board member of the business (FINTRAC beneficial owners ≥ 25 %). */
public record Principal(
        String legalName, PrincipalRole role, @Nullable BigDecimal ownershipPct) {
    public Principal {
        legalName = legalName.strip();
    }

    /** Whether this person needs identity verification at a KYC threshold of {@code pct} % (0 = everyone). */
    public boolean holdsAtLeast(int pct) {
        return pct == 0 || (ownershipPct != null && ownershipPct.compareTo(BigDecimal.valueOf(pct)) >= 0);
    }
}
