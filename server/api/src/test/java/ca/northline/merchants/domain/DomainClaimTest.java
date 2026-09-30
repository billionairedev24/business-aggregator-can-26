package ca.northline.merchants.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.merchants.domain.CustomDomain.Status;
import ca.northline.merchants.domain.DnsFindings.Pointing;
import ca.northline.merchants.domain.DnsFindings.Txt;
import ca.northline.merchants.domain.DomainClaim.Notice;
import ca.northline.shared.RuleViolation;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** S-31: the custom-domain state machine (pending → verified → issuing → live, failed, expired, grace period). */
class DomainClaimTest {

    static final Instant T0 = Instant.parse("2026-10-01T15:00:00Z");
    static final DomainPolicy POLICY = DomainPolicy.DEFAULT;
    static final DnsFindings OK = new DnsFindings(Txt.FOUND, Pointing.CNAME);
    static final DnsFindings NO_TXT = new DnsFindings(Txt.MISSING, Pointing.CNAME);
    static final DnsFindings ELSEWHERE = new DnsFindings(Txt.FOUND, Pointing.ELSEWHERE);
    static final DnsFindings DNS_DOWN = new DnsFindings(Txt.UNKNOWN, Pointing.UNKNOWN);

    static DomainClaim pending() {
        return DomainClaim.start(new CustomDomain("book.aspen.ca"), "nl-token", T0);
    }

    static DomainClaim live() {
        var verified = pending().checked(OK, POLICY, T0).claim();
        var issuing = verified.edge(new EdgeObservation.Provisioning(true), POLICY, T0)
                .claim();
        return issuing.edge(EdgeObservation.READY, POLICY, T0.plusSeconds(90)).claim();
    }

    @Nested
    class Pending {

        @Test
        void startsPendingAndDueAtOnce() {
            var claim = pending();
            assertThat(claim.status()).isEqualTo(Status.PENDING);
            assertThat(claim.nextCheckAt()).isEqualTo(T0);
            assertThat(claim.token()).isEqualTo("nl-token");
        }

        @Test
        void recordsFound_verified() {
            var outcome = pending().checked(OK, POLICY, T0.plusSeconds(60));
            assertThat(outcome.claim().status()).isEqualTo(Status.VERIFIED);
            assertThat(outcome.claim().verifiedAt()).isEqualTo(T0.plusSeconds(60));
            assertThat(outcome.claim().problem()).isNull();
            assertThat(outcome.claim().nextCheckAt())
                    .isEqualTo(T0.plusSeconds(60).plus(POLICY.recheck()));
            assertThat(outcome.notice()).isNull();
        }

        @Test
        void recordsMissing_staysPendingWithTheProblem_checkedLessOftenOverTime() {
            var first = pending().checked(NO_TXT, POLICY, T0.plusSeconds(60)).claim();
            assertThat(first.status()).isEqualTo(Status.PENDING);
            assertThat(first.problem()).isEqualTo(DomainProblem.TXT_MISSING);
            assertThat(first.nextCheckAt()).isEqualTo(T0.plusSeconds(60).plus(Duration.ofMinutes(5)));

            var later = first.checked(ELSEWHERE, POLICY, T0.plus(Duration.ofHours(3)))
                    .claim();
            assertThat(later.problem()).isEqualTo(DomainProblem.NOT_POINTING);
            assertThat(later.nextCheckAt())
                    .isEqualTo(T0.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(30)));

            var days =
                    later.checked(NO_TXT, POLICY, T0.plus(Duration.ofDays(2))).claim();
            assertThat(days.nextCheckAt()).isEqualTo(T0.plus(Duration.ofDays(2)).plus(Duration.ofHours(2)));
            assertThat(days.statusSince()).isEqualTo(T0);
        }

        @Test
        void neverCheckedPastTheWindow() {
            var claim = pending()
                    .checked(NO_TXT, POLICY, T0.plus(Duration.ofDays(7)).minusSeconds(60))
                    .claim();
            assertThat(claim.nextCheckAt()).isEqualTo(T0.plus(Duration.ofDays(7)));
        }

        @Test
        void notVerifiedWithinSevenDays_expired_checkedOnRequestOnly() {
            var outcome = pending().checked(NO_TXT, POLICY, T0.plus(Duration.ofDays(7)));
            assertThat(outcome.claim().status()).isEqualTo(Status.EXPIRED);
            assertThat(outcome.claim().nextCheckAt()).isNull();
            assertThat(outcome.notice()).isEqualTo(Notice.EXPIRED);
        }

        @Test
        void expired_checkNow_startsANewWindow_orVerifies() {
            var expired = pending()
                    .checked(NO_TXT, POLICY, T0.plus(Duration.ofDays(7)))
                    .claim();
            var again = expired.checked(NO_TXT, POLICY, T0.plus(Duration.ofDays(9)));
            assertThat(again.claim().status()).isEqualTo(Status.PENDING);
            assertThat(again.claim().statusSince()).isEqualTo(T0.plus(Duration.ofDays(9)));
            assertThat(again.claim().nextCheckAt()).isNotNull();
            assertThat(again.notice()).isNull();

            assertThat(expired.checked(OK, POLICY, T0.plus(Duration.ofDays(9)))
                            .claim()
                            .status())
                    .isEqualTo(Status.VERIFIED);
        }

        @Test
        void edgeReportsAreIgnoredUntilProven() {
            var claim = pending();
            assertThat(claim.edge(EdgeObservation.READY, POLICY, T0).claim()).isEqualTo(claim);
        }
    }

    @Nested
    class OnTheEdge {

        @Test
        void verified_issuing_live_withOneNotice() {
            var verified = pending().checked(OK, POLICY, T0).claim();
            var issuing = verified.edge(new EdgeObservation.Provisioning(true), POLICY, T0.plusSeconds(60));
            assertThat(issuing.claim().status()).isEqualTo(Status.ISSUING);
            assertThat(issuing.claim().requestedAt()).isEqualTo(T0.plusSeconds(60));
            assertThat(issuing.notice()).isNull();

            var stillIssuing =
                    issuing.claim().edge(new EdgeObservation.Provisioning(false), POLICY, T0.plusSeconds(120));
            assertThat(stillIssuing.claim()).isEqualTo(issuing.claim());

            var live = issuing.claim().edge(EdgeObservation.READY, POLICY, T0.plusSeconds(180));
            assertThat(live.claim().status()).isEqualTo(Status.LIVE);
            assertThat(live.claim().liveAt()).isEqualTo(T0.plusSeconds(180));
            assertThat(live.notice()).isEqualTo(Notice.LIVE);
            assertThat(live.claim()
                            .edge(EdgeObservation.READY, POLICY, T0.plusSeconds(240))
                            .notice())
                    .isNull();
        }

        @Test
        void certificateRefused_failed_thenRetriedAFewTimes() {
            var issuing = pending()
                    .checked(OK, POLICY, T0)
                    .claim()
                    .edge(new EdgeObservation.Provisioning(true), POLICY, T0)
                    .claim();
            var failed =
                    issuing.edge(new EdgeObservation.Failed("HTTP-01 challenge failed"), POLICY, T0.plusSeconds(300));
            assertThat(failed.claim().status()).isEqualTo(Status.FAILED);
            assertThat(failed.claim().problem()).isEqualTo(DomainProblem.CERTIFICATE);
            assertThat(failed.claim().failures()).isEqualTo(1);
            assertThat(failed.claim().nextCheckAt())
                    .isEqualTo(T0.plusSeconds(300).plus(POLICY.failedRecheck()));
            assertThat(failed.notice()).isEqualTo(Notice.CERTIFICATE_FAILED);

            // the re-check finds DNS still fine: verified again, so the edge tries once more
            var retried = failed.claim()
                    .checked(OK, POLICY, T0.plus(Duration.ofHours(7)))
                    .claim();
            assertThat(retried.status()).isEqualTo(Status.VERIFIED);
            assertThat(retried.failures()).isEqualTo(1);

            var claim = retried;
            for (int i = 2; i <= POLICY.maxFailures(); i++) {
                claim = claim.edge(new EdgeObservation.Provisioning(true), POLICY, T0)
                        .claim();
                claim = claim.edge(new EdgeObservation.Failed("again"), POLICY, T0)
                        .claim();
                assertThat(claim.failures()).isEqualTo(i);
                if (i < POLICY.maxFailures()) {
                    claim = claim.checked(OK, POLICY, T0).claim();
                }
            }
            assertThat(claim.status()).isEqualTo(Status.FAILED);
            assertThat(claim.nextCheckAt())
                    .as("only the merchant's Check now retries now")
                    .isNull();
        }

        @Test
        void failed_andDnsGone_backToPending() {
            var failed = pending()
                    .checked(OK, POLICY, T0)
                    .claim()
                    .edge(new EdgeObservation.Failed("x"), POLICY, T0)
                    .claim();
            var outcome = failed.checked(ELSEWHERE, POLICY, T0.plusSeconds(60));
            assertThat(outcome.claim().status()).isEqualTo(Status.PENDING);
            assertThat(outcome.claim().problem()).isEqualTo(DomainProblem.NOT_POINTING);
        }

        @Test
        void pageUnpublishedOrBusinessPaused_offTheEdge_verified() {
            var outcome = live().edge(EdgeObservation.ABSENT, POLICY, T0.plus(Duration.ofDays(1)));
            assertThat(outcome.claim().status()).isEqualTo(Status.VERIFIED);
            assertThat(outcome.claim().liveAt()).isNull();
            assertThat(outcome.notice()).isNull();
        }

        @Test
        void rateLimited_staysVerifiedWithTheReason() {
            var verified = pending().checked(OK, POLICY, T0).claim();
            var outcome = verified.edge(new EdgeObservation.Deferred(DomainProblem.RATE_LIMITED), POLICY, T0);
            assertThat(outcome.claim().status()).isEqualTo(Status.VERIFIED);
            assertThat(outcome.claim().problem()).isEqualTo(DomainProblem.RATE_LIMITED);
            assertThat(outcome.claim()
                            .checked(OK, POLICY, T0.plusSeconds(60))
                            .claim()
                            .problem())
                    .as("a DNS re-check keeps the edge's reason")
                    .isEqualTo(DomainProblem.RATE_LIMITED);
            assertThat(outcome.claim()
                            .edge(new EdgeObservation.Provisioning(true), POLICY, T0)
                            .claim()
                            .problem())
                    .isNull();
        }
    }

    @Nested
    class GracePeriod {

        @Test
        void recordsGone_keepsServing_ownersToldOnce() {
            var at = T0.plus(Duration.ofDays(3));
            var lost = live().checked(NO_TXT, POLICY, at);
            assertThat(lost.claim().status()).isEqualTo(Status.LIVE);
            assertThat(lost.claim().dnsLostAt()).isEqualTo(at);
            assertThat(lost.claim().graceEndsAt(POLICY)).isEqualTo(at.plus(POLICY.grace()));
            assertThat(lost.claim().problem()).isEqualTo(DomainProblem.TXT_MISSING);
            assertThat(lost.claim().nextCheckAt()).isEqualTo(at.plus(POLICY.lostRecheck()));
            assertThat(lost.notice()).isEqualTo(Notice.DNS_LOST);

            var still = lost.claim().checked(ELSEWHERE, POLICY, at.plus(Duration.ofHours(1)));
            assertThat(still.claim().dnsLostAt()).isEqualTo(at);
            assertThat(still.notice()).isNull();
        }

        @Test
        void recordsBack_withinTheGrace_forgiven() {
            var at = T0.plus(Duration.ofDays(3));
            var lost = live().checked(NO_TXT, POLICY, at).claim();
            var back = lost.checked(OK, POLICY, at.plus(Duration.ofHours(5)));
            assertThat(back.claim().status()).isEqualTo(Status.LIVE);
            assertThat(back.claim().dnsLostAt()).isNull();
            assertThat(back.claim().problem()).isNull();
            assertThat(back.claim().graceEndsAt(POLICY)).isNull();
        }

        @Test
        void graceOver_backToPending_offTheEdge() {
            var at = T0.plus(Duration.ofDays(3));
            var lost = live().checked(NO_TXT, POLICY, at).claim();
            var over = lost.checked(ELSEWHERE, POLICY, at.plus(POLICY.grace()));
            assertThat(over.claim().status()).isEqualTo(Status.PENDING);
            assertThat(over.claim().verifiedAt()).isNull();
            assertThat(over.claim().liveAt()).isNull();
            assertThat(over.claim().dnsLostAt()).isNull();
            assertThat(over.claim().statusSince()).isEqualTo(at.plus(POLICY.grace()));
            assertThat(over.notice()).isEqualTo(Notice.UNVERIFIED);
        }

        @Test
        void checksNeverOvershootTheEndOfTheGrace() {
            var at = T0.plus(Duration.ofDays(3));
            var lost = live().checked(NO_TXT, POLICY, at).claim();
            var nearEnd = lost.checked(NO_TXT, POLICY, at.plus(POLICY.grace()).minusSeconds(60))
                    .claim();
            assertThat(nearEnd.nextCheckAt()).isEqualTo(at.plus(POLICY.grace()));
        }

        @Test
        void dnsUnreachable_nothingChangesButTheNextCheck() {
            var claim = live();
            var outcome = claim.checked(DNS_DOWN, POLICY, T0.plus(Duration.ofDays(1)));
            assertThat(outcome.claim().status()).isEqualTo(Status.LIVE);
            assertThat(outcome.claim().dnsLostAt()).isNull();
            assertThat(outcome.claim().nextCheckAt())
                    .isEqualTo(T0.plus(Duration.ofDays(1)).plus(POLICY.lostRecheck()));
            assertThat(outcome.notice()).isNull();
        }
    }

    @Nested
    class Findings {

        @ParameterizedTest(name = "{0} + {1} → {2}")
        @CsvSource({
            "FOUND,    CNAME,     ,             true,  false",
            "FOUND,    ADDRESS,   ,             true,  false",
            "MISSING,  CNAME,     TXT_MISSING,  false, false",
            "MISMATCH, CNAME,     TXT_MISMATCH, false, false",
            "FOUND,    MISSING,   NO_RECORD,    false, false",
            "FOUND,    ELSEWHERE, NOT_POINTING, false, false",
            "UNKNOWN,  CNAME,     DNS_ERROR,    false, true",
            "FOUND,    UNKNOWN,   DNS_ERROR,    false, true",
            "UNKNOWN,  ELSEWHERE, NOT_POINTING, false, false",
        })
        void problemAndCertainty(Txt txt, Pointing pointing, DomainProblem problem, boolean ok, boolean inconclusive) {
            var findings = new DnsFindings(txt, pointing);
            assertThat(findings.ok()).isEqualTo(ok);
            assertThat(findings.inconclusive()).isEqualTo(inconclusive);
            assertThat(findings.problem()).isEqualTo(problem);
        }
    }

    @Nested
    class Domains {

        @ParameterizedTest
        @CsvSource({
            "Book.Aspen.CA,            book.aspen.ca,             false",
            "https://shop.aspen.ca/x,  shop.aspen.ca,             false",
            "aspen.ca.,                aspen.ca,                  true",
            "aspen.on.ca,              aspen.on.ca,               true",
            "book.aspen.on.ca,         book.aspen.on.ca,          false",
            "café.ca,                  xn--caf-dma.ca,            true",
        })
        void normalisedAndApexKnown(String raw, String value, boolean apex) {
            var domain = new CustomDomain(raw);
            assertThat(domain.value()).isEqualTo(value);
            assertThat(domain.isApex()).isEqualTo(apex);
            assertThat(domain.verificationName()).isEqualTo("_northline-verify." + value);
        }

        @ParameterizedTest
        @CsvSource({"northline.ca", "pages.northline.ca", "localhost", "not a domain", "192.168.0.1", "-bad-.ca"})
        void refused(String raw) {
            assertThatThrownBy(() -> new CustomDomain(raw))
                    .isInstanceOf(RuleViolation.class)
                    .hasMessageContaining(CustomDomain.FORMAT);
        }
    }
}
