package ca.northline.merchants.application;

import ca.northline.merchants.api.CustomDomainChanged;
import ca.northline.merchants.application.StorefrontRepository.ClaimRow;
import ca.northline.merchants.domain.CustomDomain;
import ca.northline.merchants.domain.DomainClaim;
import ca.northline.merchants.domain.DomainClaim.Notice;
import ca.northline.shared.CodedEnums;
import ca.northline.shared.Ids;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Records custom-domain transitions (S-31) and announces them with {@link CustomDomainChanged}. */
@Component
@RequiredArgsConstructor
class DomainTransitions {

    private final StorefrontRepository storefronts;
    private final DomainSettings settings;
    private final ApplicationEventPublisher events;

    /**
     * Writes a transition — only while that claim is still the storefront's — and announces a change of state,
     * problem or grace period, or a notice.
     *
     * @return whether the claim was written
     */
    boolean apply(ClaimRow row, DomainClaim.Outcome outcome, Instant now) {
        return apply(row.storefrontId(), row.merchantId(), row.claim(), outcome, now);
    }

    boolean apply(
            String storefrontId, String merchantId, DomainClaim before, DomainClaim.Outcome outcome, Instant now) {
        var after = outcome.claim();
        if (after.equals(before) && outcome.notice() == null) {
            return false;
        }
        if (!storefronts.saveClaim(storefrontId, before.token(), after)) {
            return false;
        }
        if (before.status() != after.status()
                || before.problem() != after.problem()
                || !Objects.equals(before.dnsLostAt(), after.dnsLostAt())
                || outcome.notice() != null) {
            announce(storefrontId, merchantId, after.domain(), after, before.status(), outcome.notice(), now);
        }
        return true;
    }

    /**
     * @param current the claim now, null = the domain is no longer this page's
     * @param previous the status before, null = newly connected
     */
    void announce(
            String storefrontId,
            String merchantId,
            CustomDomain domain,
            @Nullable DomainClaim current,
            CustomDomain.@Nullable Status previous,
            @Nullable Notice notice,
            Instant now) {
        events.publishEvent(new CustomDomainChanged(
                Ids.next(),
                now,
                storefrontId,
                merchantId,
                domain.value(),
                current == null ? null : current.status().code(),
                CodedEnums.toCode(previous),
                current == null ? null : CodedEnums.toCode(current.problem()),
                CodedEnums.toCode(notice),
                current == null ? null : current.graceEndsAt(settings.policy())));
    }
}
