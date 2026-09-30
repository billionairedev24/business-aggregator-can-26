package ca.northline.merchants.application;

import ca.northline.merchants.domain.CustomDomain;
import ca.northline.merchants.domain.Storefront;
import ca.northline.shared.RuleViolation;
import java.security.SecureRandom;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Connecting a custom domain to a storefront (S-31): our own zones are off limits, and one page per domain. */
@Component
@RequiredArgsConstructor
class DomainClaims {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    private final StorefrontRepository storefronts;
    private final DomainSettings settings;
    private final DomainConflicts conflicts;
    private final DomainTransitions transitions;

    /**
     * Connects {@code raw} ({@code ""} = none) to the storefront, whose row the caller has locked. The same domain again
     * changes nothing; a new one starts pending with a fresh token.
     */
    void connect(Storefront storefront, String raw, Instant now) {
        var domain = raw.isBlank() ? null : new CustomDomain(raw);
        if (domain != null && settings.blocked(domain)) {
            throw RuleViolation.of(CustomDomain.FIELD, "blocked", CustomDomain.BLOCKED);
        }
        var before = storefront.getDomainClaim();
        if (domain != null && domain.equals(storefront.getCustomDomain())) {
            return;
        }
        if (domain != null && !conflicts.reverify(domain, storefront.getId(), now)) {
            throw RuleViolation.of(CustomDomain.FIELD, "unique", CustomDomain.TAKEN);
        }
        if (!storefront.connectDomain(domain, token(), now)) {
            return;
        }
        var after = storefront.getDomainClaim();
        storefronts.saveClaim(storefront.getId(), null, after);
        if (before != null) {
            transitions.announce(
                    storefront.getId(), storefront.getMerchantId(), before.domain(), null, before.status(), null, now);
        }
        if (after != null) {
            transitions.announce(
                    storefront.getId(), storefront.getMerchantId(), after.domain(), after, null, null, now);
        }
    }

    /** The TXT value proving ownership: {@code nl-} + 160 random bits in base32. */
    static String token() {
        var out = new StringBuilder("nl-");
        for (int i = 0; i < 32; i++) {
            out.append(BASE32[RANDOM.nextInt(BASE32.length)]);
        }
        return out.toString();
    }
}
