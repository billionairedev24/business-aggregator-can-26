package ca.northline.merchants.application;

import ca.northline.merchants.application.StorefrontUseCases.CustomDomainJobs;
import ca.northline.merchants.application.StorefrontUseCases.ResolveStorefrontHost;
import ca.northline.merchants.application.StorefrontUseCases.SimulateDomainRecords;
import ca.northline.merchants.application.StorefrontUseCases.StorefrontView;
import ca.northline.merchants.application.StorefrontUseCases.VerifyCustomDomain;
import ca.northline.merchants.domain.CustomDomain;
import ca.northline.merchants.domain.CustomDomain.Status;
import ca.northline.merchants.domain.DomainClaim;
import ca.northline.merchants.domain.DomainProblem;
import ca.northline.merchants.domain.EdgeObservation;
import ca.northline.merchants.domain.MerchantStatus;
import ca.northline.merchants.domain.Storefront;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom domains after they are entered (S-31): DNS checks (on request and scheduled), the edge (listeners and
 * certificates for the domains that may serve), and the public host → page lookup.
 *
 * <p>A domain may serve when it is proven (TXT + CNAME/A), the business is active and its page published. New
 * certificates are rationed: {@code DOMAINS_ISSUE_PER_HOUR} across all merchants, and one request per page per
 * {@code DOMAINS_REQUEST_COOLDOWN} (Let's Encrypt limits: docs/runbooks/custom-domains.md).
 */
@Service
@RequiredArgsConstructor
@Transactional
class CustomDomainService
        implements VerifyCustomDomain, SimulateDomainRecords, ResolveStorefrontHost, CustomDomainJobs {

    private final StorefrontRepository storefronts;
    private final StorefrontViews views;
    private final DnsInspector inspector;
    private final DnsResolver dns;
    private final DomainEdge edge;
    private final DomainTransitions transitions;
    private final DomainSettings settings;
    private final ObjectProvider<DnsSandbox> sandbox;
    private final Clock clock;

    @Override
    public StorefrontView verify(String merchantId) {
        var storefront = lock(merchantId);
        check(storefront, false);
        return views.of(storefront);
    }

    @Override
    public StorefrontView simulate(String merchantId) {
        var zone = sandbox.getIfAvailable();
        if (zone == null) {
            throw new NotFound("dns sandbox", merchantId);
        }
        var storefront = lock(merchantId);
        var claim = claim(storefront);
        var domain = claim.domain();
        if (domain.isApex()) {
            var addresses = settings.edgeAddresses().isEmpty()
                    ? dns.lookup(settings.targetHost(), DnsResolver.Type.A).values()
                    : settings.edgeAddresses();
            zone.publish(domain.value(), DnsResolver.Type.A, addresses);
        } else {
            zone.publish(domain.value(), DnsResolver.Type.CNAME, List.of(settings.targetHost()));
        }
        zone.publish(domain.verificationName(), DnsResolver.Type.TXT, List.of(claim.token()));
        check(storefront, true);
        reconcileEdge();
        return views.of(lock(merchantId));
    }

    @Override
    @Transactional(readOnly = true)
    public StorefrontView byHost(String host) {
        CustomDomain domain;
        try {
            domain = new CustomDomain(host.replaceFirst(":\\d+$", ""));
        } catch (RuleViolation _) {
            throw new NotFound("storefront", host);
        }
        return storefronts
                .findLiveByDomain(domain.value())
                .filter(s -> s.getPublishedAt() != null)
                .map(views::of)
                .filter(v -> v.merchant().getStatus() == MerchantStatus.ACTIVE)
                .orElseThrow(() -> new NotFound("storefront", domain.value()));
    }

    @Override
    public int checkDue() {
        var now = clock.instant();
        var changed = 0;
        for (var row : storefronts.lockDueClaims(now, settings.checkBatch())) {
            var claim = row.claim();
            var outcome = claim.checked(inspector.inspect(claim.domain(), claim.token()), settings.policy(), now);
            if (transitions.apply(row, outcome, now) && outcome.claim().status() != claim.status()) {
                changed++;
            }
        }
        return changed;
    }

    @Override
    public int reconcileEdge() {
        if (!storefronts.tryEdgeLock()) {
            return 0;
        }
        var now = clock.instant();
        var rows = storefronts.lockProvenClaims();
        var wanted = new LinkedHashSet<String>();
        var held = new HashMap<String, EdgeObservation>();
        for (var row : rows) {
            var claim = row.claim();
            if (!row.merchantActive() || !row.published()) {
                continue;
            }
            if (claim.status() == Status.VERIFIED && coolingDown(claim, now)) {
                held.put(claim.domain().value(), new EdgeObservation.Deferred(DomainProblem.RATE_LIMITED));
                continue;
            }
            wanted.add(claim.domain().value());
        }
        var allowance = Math.max(
                0, settings.issuePerHour() - storefronts.certificateRequestsSince(now.minus(Duration.ofHours(1))));
        var seen = edge.reconcile(wanted, allowance);
        var changed = 0;
        for (var row : rows) {
            var host = row.claim().domain().value();
            var observed = held.getOrDefault(host, seen.getOrDefault(host, EdgeObservation.ABSENT));
            var outcome = row.claim().edge(observed, settings.policy(), now);
            if (transitions.apply(row, outcome, now)
                    && outcome.claim().status() != row.claim().status()) {
                changed++;
            }
        }
        return changed;
    }

    /** "Check now" (at most every {@code DOMAINS_CHECK_COOLDOWN} unless forced) and the dev simulation. */
    private void check(Storefront storefront, boolean force) {
        var claim = claim(storefront);
        var now = clock.instant();
        var last = claim.checkedAt();
        if (!force && last != null && now.isBefore(last.plus(settings.checkCooldown()))) {
            return;
        }
        var outcome = claim.checked(inspector.inspect(claim.domain(), claim.token()), settings.policy(), now);
        if (transitions.apply(storefront.getId(), storefront.getMerchantId(), claim, outcome, now)) {
            storefront.advanceDomain(outcome.claim());
        }
    }

    private boolean coolingDown(DomainClaim claim, Instant now) {
        var requested = claim.requestedAt();
        return requested != null && now.isBefore(requested.plus(settings.requestCooldown()));
    }

    private static DomainClaim claim(Storefront storefront) {
        var claim = storefront.getDomainClaim();
        if (claim == null) {
            throw new Conflict("no_custom_domain", "Add a custom domain first.");
        }
        return claim;
    }

    private Storefront lock(String merchantId) {
        return storefronts.lockByMerchant(merchantId).orElseThrow(() -> new NotFound("storefront", merchantId));
    }
}
