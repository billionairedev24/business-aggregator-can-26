package ca.northline.merchants.application;

import ca.northline.merchants.domain.CustomDomain;
import ca.northline.merchants.domain.DnsFindings.Txt;
import ca.northline.merchants.domain.DomainClaim.Notice;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-verification on a claim conflict (S-31): a domain is connected to one page only, so when another page asks for it
 * the holder's claim is checked again — in its own transaction, committed even if the claimant's request then fails.
 */
@Component
@RequiredArgsConstructor
class DomainConflicts {

    private final StorefrontRepository storefronts;
    private final DnsInspector inspector;
    private final DomainSettings settings;
    private final DomainTransitions transitions;

    /**
     * A page whose domain is proven keeps it: its records are checked now, so if they are gone its grace period starts
     * and its owners hear about it (the domain frees up when the grace period ends). A page that never proved it
     * (pending, expired, failed) loses it unless its own TXT record is there — its owners are told.
     *
     * @return whether the domain is free for the claimant
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reverify(CustomDomain domain, String claimantStorefrontId, Instant now) {
        var holder = storefronts.lockClaimOf(domain, claimantStorefrontId);
        if (holder.isEmpty()) {
            return true;
        }
        var row = holder.get();
        var claim = row.claim();
        if (claim.status().proven()) {
            transitions.apply(
                    row, claim.checked(inspector.inspect(domain, claim.token()), settings.policy(), now), now);
            return false;
        }
        var txt = inspector.txt(domain, claim.token());
        if (txt == Txt.FOUND || txt == Txt.UNKNOWN) {
            return false;
        }
        storefronts.saveClaim(row.storefrontId(), claim.token(), null);
        transitions.announce(row.storefrontId(), row.merchantId(), domain, null, claim.status(), Notice.RELEASED, now);
        return true;
    }
}
