package ca.northline.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** S-162: Plus renewal dates from an RFC 5545 RRULE — month-ends, leap days, years — and the retry schedule. */
class BillingRuleTest {

    private static List<Instant> first(BillingRule rule, int n) {
        return IntStream.range(0, n).mapToObj(rule::occurrence).toList();
    }

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void monthly_fromAnOrdinaryDay_keepsTheDayAndTheTime_inUtc() {
        var rule = BillingRule.anchored(BillingRule.MONTHLY, at("2027-01-15T18:42:07.912Z"));
        assertThat(rule.rrule()).isEqualTo("FREQ=MONTHLY;INTERVAL=1");
        assertThat(rule.dtstart()).isEqualTo(at("2027-01-15T18:42:07Z")); // to the second
        assertThat(first(rule, 4))
                .containsExactly(
                        at("2027-01-15T18:42:07Z"),
                        at("2027-02-15T18:42:07Z"),
                        at("2027-03-15T18:42:07Z"),
                        at("2027-04-15T18:42:07Z"));
    }

    @Test
    void monthly_fromThe31st_renewsOnTheLastDayOfShorterMonths_andGoesBackToThe31st() {
        var rule = BillingRule.anchored(BillingRule.MONTHLY, at("2027-01-31T10:15:30Z"));
        assertThat(rule.rrule()).isEqualTo("FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=28,29,30,31;BYSETPOS=-1");
        assertThat(first(rule, 7))
                .containsExactly(
                        at("2027-01-31T10:15:30Z"),
                        at("2027-02-28T10:15:30Z"),
                        at("2027-03-31T10:15:30Z"),
                        at("2027-04-30T10:15:30Z"),
                        at("2027-05-31T10:15:30Z"),
                        at("2027-06-30T10:15:30Z"),
                        at("2027-07-31T10:15:30Z"));
    }

    @Test
    void plainRfc5545_wouldSkipThoseMonths_whichIsWhyTheRuleIsAnchored() {
        var naive = new BillingRule(BillingRule.MONTHLY, at("2027-01-31T10:15:30Z"));
        assertThat(naive.occurrence(1)).isEqualTo(at("2027-03-31T10:15:30Z")); // February has no 31st
    }

    @Test
    void monthly_fromThe30th_andThe29th_inALeapYear() {
        var thirtieth = BillingRule.anchored(BillingRule.MONTHLY, at("2028-01-30T00:00:00Z"));
        assertThat(first(thirtieth, 3))
                .containsExactly(at("2028-01-30T00:00:00Z"), at("2028-02-29T00:00:00Z"), at("2028-03-30T00:00:00Z"));
        var twentyNinth = BillingRule.anchored(BillingRule.MONTHLY, at("2027-01-29T00:00:00Z"));
        assertThat(twentyNinth.rrule()).endsWith(";BYMONTHDAY=28,29;BYSETPOS=-1");
        assertThat(first(twentyNinth, 3))
                .containsExactly(at("2027-01-29T00:00:00Z"), at("2027-02-28T00:00:00Z"), at("2027-03-29T00:00:00Z"));
    }

    @Test
    void yearly_fromFebruary29_renewsOnFebruary28_inCommonYears() {
        var rule = BillingRule.anchored(BillingRule.YEARLY, at("2028-02-29T08:00:00Z"));
        assertThat(rule.rrule()).isEqualTo("FREQ=YEARLY;INTERVAL=1;BYMONTH=2;BYMONTHDAY=28,29;BYSETPOS=-1");
        assertThat(first(rule, 5))
                .containsExactly(
                        at("2028-02-29T08:00:00Z"),
                        at("2029-02-28T08:00:00Z"),
                        at("2030-02-28T08:00:00Z"),
                        at("2031-02-28T08:00:00Z"),
                        at("2032-02-29T08:00:00Z"));
    }

    @Test
    void yearly_fromAnOrdinaryDay_andFromAMonthEnd() {
        var rule = BillingRule.anchored(BillingRule.YEARLY, at("2026-10-20T12:00:00Z"));
        assertThat(rule.rrule()).isEqualTo("FREQ=YEARLY;INTERVAL=1");
        assertThat(rule.after(rule.dtstart())).isEqualTo(at("2027-10-20T12:00:00Z"));
        var monthEnd = BillingRule.anchored(BillingRule.YEARLY, at("2027-08-31T12:00:00Z"));
        assertThat(monthEnd.after(monthEnd.dtstart())).isEqualTo(at("2028-08-31T12:00:00Z"));
    }

    @Test
    void after_isTheEndOfThePeriodThatContainsTheInstant() {
        var rule = BillingRule.anchored(BillingRule.MONTHLY, at("2027-01-31T10:15:30Z"));
        assertThat(rule.after(at("2027-01-31T10:15:30Z"))).isEqualTo(at("2027-02-28T10:15:30Z"));
        assertThat(rule.after(at("2027-02-28T10:15:29Z"))).isEqualTo(at("2027-02-28T10:15:30Z"));
        assertThat(rule.after(at("2027-02-28T10:15:30Z"))).isEqualTo(at("2027-03-31T10:15:30Z"));
        assertThat(rule.after(at("2026-12-01T00:00:00Z"))).isEqualTo(at("2027-01-31T10:15:30Z"));
        // a membership that ran for years: still exact
        assertThat(rule.after(at("2031-05-01T00:00:00Z"))).isEqualTo(at("2031-05-31T10:15:30Z"));
    }

    @Test
    void everyOtherMonth_andTheRfc5545Prefix_areAccepted() {
        var rule = BillingRule.anchored("RRULE:freq=monthly;interval=2", at("2027-01-31T00:00:00Z"));
        assertThat(rule.after(rule.dtstart())).isEqualTo(at("2027-03-31T00:00:00Z"));
        assertThat(rule.occurrence(2)).isEqualTo(at("2027-05-31T00:00:00Z"));
    }

    @Test
    void aPlanRule_isFreqAndInterval_monthlyOrYearly_only() {
        assertThatThrownBy(() -> BillingRule.planRule("FREQ=MONTHLY;COUNT=3")).hasMessageContaining("COUNT");
        assertThatThrownBy(() -> BillingRule.planRule("FREQ=MONTHLY;UNTIL=20280101T000000Z"))
                .hasMessageContaining("UNTIL");
        assertThatThrownBy(() -> BillingRule.planRule("FREQ=WEEKLY")).hasMessageContaining("MONTHLY or FREQ=YEARLY");
        assertThatThrownBy(() -> BillingRule.planRule("FREQ=MONTHLY;INTERVAL=13"))
                .hasMessageContaining("1–12");
        assertThatThrownBy(() -> BillingRule.planRule("every month")).hasMessageContaining("Not an RFC 5545 RRULE");
        assertThat(BillingRule.planRule(" FREQ=YEARLY ")).isEqualTo("FREQ=YEARLY");
    }

    @Nested
    class Retries {

        private final RenewalRetries retries = RenewalRetries.days(List.of(1, 3, 5));
        private final Instant due = at("2027-03-31T10:15:30Z");

        @Test
        void afterEachRefusal_theNextTry_thenLapse() {
            assertThat(retries.next(due, 1)).contains(due.plus(Duration.ofDays(1)));
            assertThat(retries.next(due, 2)).contains(due.plus(Duration.ofDays(3)));
            assertThat(retries.next(due, 3)).contains(due.plus(Duration.ofDays(5)));
            assertThat(retries.next(due, 4)).isEmpty();
            assertThat(retries.last()).isEqualTo(Duration.ofDays(5));
        }

        @Test
        void retriesComeLaterEachTime() {
            assertThatThrownBy(() -> RenewalRetries.days(List.of(3, 1))).hasMessageContaining("later each time");
            assertThat(RenewalRetries.days(List.of()).next(due, 1)).isEmpty();
        }
    }
}
