package ca.northline.merchants.domain;

import ca.northline.merchants.domain.CustomDomain.Status;
import ca.northline.shared.CodedEnum;
import java.time.Instant;
import java.util.Objects;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * A storefront's custom domain and where it stands (S-31). Immutable: every transition returns an {@link Outcome}
 * with the next claim and, when the owners must hear about it, a {@link Notice}.
 *
 * <pre>
 *   pending ──DNS ok──▶ verified ──edge: requested──▶ issuing ──edge: ready──▶ live
 *      │ 7 days            ▲  ◀──────── edge: absent (unpublished, inactive) ─────┘
 *      ▼                   │ DNS ok (re-check)
 *   expired ─check now─▶ pending / verified        issuing ──edge: failed──▶ failed ──re-check──▶ verified | pending
 *   verified/issuing/live: DNS lost → keeps serving for the grace period (notice), then → pending (notice)
 * </pre>
 *
 * @param token value of the TXT record at {@link CustomDomain#verificationName()}
 * @param statusSince when {@link #status} was entered
 * @param nextCheckAt next scheduled DNS check; null = only when the merchant asks
 * @param dnsLostAt a proven domain stopped pointing at us (its grace period runs from here)
 * @param requestedAt last certificate request (Let's Encrypt rate limits)
 * @param failures certificate failures in a row
 */
@Builder(toBuilder = true)
public record DomainClaim(
        CustomDomain domain,
        String token,
        Status status,
        Instant statusSince,
        @Nullable Instant verifiedAt,
        @Nullable Instant checkedAt,
        @Nullable Instant nextCheckAt,
        @Nullable DomainProblem problem,
        @Nullable Instant dnsLostAt,
        @Nullable Instant liveAt,
        @Nullable Instant requestedAt,
        int failures) {

    /** Why the owners get an email about their domain. */
    public enum Notice implements CodedEnum {
        LIVE,
        /** The records no longer point at us; it keeps serving until {@link #graceEndsAt}. */
        DNS_LOST,
        /** The grace period ended: back to pending, no longer served. */
        UNVERIFIED,
        CERTIFICATE_FAILED,
        EXPIRED,
        /** Another business claimed the domain while this page's TXT record was absent: disconnected. */
        RELEASED
    }

    /** The next claim and the notice for the owners, if any. */
    public record Outcome(DomainClaim claim, @Nullable Notice notice) {}

    /** A newly entered domain: pending, checked right away. */
    public static DomainClaim start(CustomDomain domain, String token, Instant now) {
        return new DomainClaim(domain, token, Status.PENDING, now, null, null, now, null, null, null, null, 0);
    }

    /** End of the grace period while the records don't point at us, else null. */
    public @Nullable Instant graceEndsAt(DomainPolicy policy) {
        return dnsLostAt == null ? null : dnsLostAt.plus(policy.grace());
    }

    /** Applies a DNS check. {@code EXPIRED} and {@code FAILED} domains get here by the merchant's "Check now" too. */
    public Outcome checked(DnsFindings found, DomainPolicy policy, Instant now) {
        var at = toBuilder().checkedAt(now).build();
        return switch (status) {
            case PENDING, EXPIRED -> at.unproven(found, policy, now);
            case VERIFIED, ISSUING, LIVE -> at.proven(found, policy, now);
            case FAILED -> {
                if (found.ok()) {
                    yield new Outcome(
                            at.enter(Status.VERIFIED, now)
                                    .problem(null)
                                    .nextCheckAt(now.plus(policy.recheck()))
                                    .build(),
                            null);
                }
                if (found.inconclusive()) {
                    yield new Outcome(
                            at.toBuilder()
                                    .nextCheckAt(now.plus(policy.failedRecheck()))
                                    .build(),
                            null);
                }
                yield new Outcome(at.backToPending(found, policy, now), null);
            }
        };
    }

    private Outcome unproven(DnsFindings found, DomainPolicy policy, Instant now) {
        if (found.ok()) {
            return new Outcome(
                    enter(Status.VERIFIED, now)
                            .verifiedAt(now)
                            .problem(null)
                            .nextCheckAt(now.plus(policy.recheck()))
                            .build(),
                    null);
        }
        if (status == Status.EXPIRED) { // "Check now" on an expired domain starts a new window
            return new Outcome(backToPending(found, policy, now), null);
        }
        var problem = found.problem();
        if (!now.isBefore(statusSince.plus(policy.verifyWindow()))) {
            return new Outcome(
                    enter(Status.EXPIRED, now)
                            .problem(problem)
                            .nextCheckAt(null)
                            .build(),
                    Notice.EXPIRED);
        }
        return new Outcome(
                toBuilder()
                        .problem(problem)
                        .nextCheckAt(policy.pendingNext(now, statusSince))
                        .build(),
                null);
    }

    private Outcome proven(DnsFindings found, DomainPolicy policy, Instant now) {
        if (found.ok()) {
            var keep = problem == DomainProblem.CAPACITY || problem == DomainProblem.RATE_LIMITED ? problem : null;
            return new Outcome(
                    toBuilder()
                            .dnsLostAt(null)
                            .problem(keep)
                            .nextCheckAt(now.plus(policy.recheck()))
                            .build(),
                    null);
        }
        if (found.inconclusive()) {
            return new Outcome(
                    toBuilder().nextCheckAt(now.plus(policy.lostRecheck())).build(), null);
        }
        var lost = Objects.requireNonNullElse(dnsLostAt, now);
        var graceEnds = lost.plus(policy.grace());
        if (!now.isBefore(graceEnds)) {
            return new Outcome(backToPending(found, policy, now), Notice.UNVERIFIED);
        }
        var next = now.plus(policy.lostRecheck());
        return new Outcome(
                toBuilder()
                        .dnsLostAt(lost)
                        .problem(found.problem())
                        .nextCheckAt(next.isAfter(graceEnds) ? graceEnds : next)
                        .build(),
                dnsLostAt == null ? Notice.DNS_LOST : null);
    }

    /** Applies what the edge reported. Only proven domains are on the edge; the others ignore it. */
    public Outcome edge(EdgeObservation seen, DomainPolicy policy, Instant now) {
        if (!status.proven()) {
            return new Outcome(this, null);
        }
        return switch (seen) {
            case EdgeObservation.Ready _ ->
                status == Status.LIVE
                        ? new Outcome(this, null)
                        : new Outcome(
                                enter(Status.LIVE, now)
                                        .liveAt(now)
                                        .failures(0)
                                        .problem(dnsLostAt == null ? null : problem)
                                        .build(),
                                Notice.LIVE);
            case EdgeObservation.Provisioning p -> {
                var requested = p.requested() ? now : requestedAt;
                if (status == Status.ISSUING) {
                    yield new Outcome(toBuilder().requestedAt(requested).build(), null);
                }
                yield new Outcome(
                        enter(Status.ISSUING, now)
                                .requestedAt(requested)
                                .liveAt(null)
                                .problem(dnsLostAt == null ? null : problem)
                                .build(),
                        null);
            }
            case EdgeObservation.Failed _ -> {
                var failed = failures + 1;
                yield new Outcome(
                        enter(Status.FAILED, now)
                                .failures(failed)
                                .liveAt(null)
                                .problem(DomainProblem.CERTIFICATE)
                                .nextCheckAt(failed >= policy.maxFailures() ? null : now.plus(policy.failedRecheck()))
                                .build(),
                        Notice.CERTIFICATE_FAILED);
            }
            case EdgeObservation.Deferred d ->
                status == Status.VERIFIED && dnsLostAt == null && problem != d.problem()
                        ? new Outcome(toBuilder().problem(d.problem()).build(), null)
                        : new Outcome(this, null);
            case EdgeObservation.Absent _ ->
                status == Status.VERIFIED
                        ? new Outcome(this, null)
                        : new Outcome(enter(Status.VERIFIED, now).liveAt(null).build(), null);
        };
    }

    private DomainClaim backToPending(DnsFindings found, DomainPolicy policy, Instant now) {
        return enter(Status.PENDING, now)
                .verifiedAt(null)
                .liveAt(null)
                .dnsLostAt(null)
                .problem(found.problem())
                .nextCheckAt(policy.pendingNext(now, now))
                .build();
    }

    private DomainClaimBuilder enter(Status next, Instant now) {
        return toBuilder().status(next).statusSince(now);
    }
}
