package ca.northline.console.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.console.application.PilotStages.Inputs;
import ca.northline.console.application.PilotStages.Listings;
import ca.northline.merchants.api.KitchenVisits;
import ca.northline.merchants.api.PilotCohort.Business;
import ca.northline.merchants.api.PilotCohort.Invite;
import ca.northline.merchants.api.PilotCohort.Pilot;
import ca.northline.payments.api.ConnectReadiness;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** S-120: a pilot business's stage and next action, from the owning modules' facts (no database). */
class PilotStagesTest {

    static final Instant AT = Instant.parse("2027-07-26T16:00:00Z");
    static final ConnectReadiness.Account READY = new ConnectReadiness.Account("acct_1", true, true, 0, 0, null);

    static Business business(
            String type,
            String status,
            boolean visitRequired,
            @Nullable String siteVisit,
            KitchenVisits.@Nullable Visit visit,
            boolean published,
            @Nullable String hidden) {
        return new Business(
                "Pho Mai",
                type,
                status,
                "done",
                "AB",
                "Pilotville",
                true,
                12,
                12,
                List.of(),
                "verified",
                false,
                0,
                visitRequired,
                siteVisit,
                null,
                visit,
                AT,
                "active".equals(status) ? AT : null,
                published,
                hidden);
    }

    static Pilot pilot(String type, @Nullable Business b) {
        return new Pilot(
                "p1",
                "mkt-x",
                type,
                "Pho Mai",
                b == null ? null : "m1",
                null,
                null,
                null,
                null,
                AT,
                new Invite(
                        "i1",
                        "a@example.test",
                        AT,
                        AT.plusSeconds(86_400),
                        b == null ? null : AT,
                        b == null ? "pending" : "accepted"),
                b);
    }

    @Test
    void anInvitedBusiness_waitsForTheOwnerToAccept() {
        var steps = PilotStages.checklist(new Inputs(pilot("seller", null), null, Listings.NONE, false));
        assertThat(PilotStages.stage(steps)).isEqualTo("invited");
        assertThat(PilotStages.next(steps)).satisfies(n -> {
            assertThat(n.key()).isEqualTo("account_created");
            assertThat(n.action()).isEqualTo("accept_invite");
            assertThat(n.owner()).isEqualTo("business");
        });
        assertThat(steps)
                .filteredOn(s -> s.key().equals("kitchen_visit"))
                .singleElement()
                .satisfies(s -> assertThat(s.state()).isEqualTo("na"));
    }

    @Test
    void aKitchenNeedingAVisit_staysBeforeTheVisitUntilItPasses() {
        var scheduled = new KitchenVisits.Visit(
                "v1", "m1", AT, null, "R. Okafor", "scheduled", Map.of(), null, List.of(), null, null);
        var b = business("kitchen", "pending", true, "submitted", scheduled, false, "pilot");
        var steps = PilotStages.checklist(new Inputs(pilot("kitchen", b), READY, Listings.of(3, 3, 0), false));
        assertThat(PilotStages.stage(steps)).isEqualTo("stripe_ready");
        assertThat(PilotStages.next(steps)).satisfies(n -> {
            assertThat(n.action()).isEqualTo("visit_on");
            assertThat(n.owner()).isEqualTo("inspector");
            assertThat(n.params()).containsEntry("at", AT.toString());
        });
    }

    @Test
    void stripeNotReported_waitsForStripe_andDisabledNeedsTheOwner() {
        var b = business("seller", "applicant", false, null, null, false, null);
        var waiting = PilotStages.checklist(new Inputs(
                pilot("seller", b),
                new ConnectReadiness.Account("acct_1", null, null, 0, 0, null),
                Listings.NONE,
                true));
        assertThat(PilotStages.next(waiting)).satisfies(n -> {
            assertThat(n.key()).isEqualTo("stripe_ready");
            assertThat(n.state()).isEqualTo("waiting");
            assertThat(n.owner()).isEqualTo("stripe");
        });
        var pastDue = PilotStages.checklist(new Inputs(
                pilot("seller", b),
                new ConnectReadiness.Account("acct_1", false, false, 0, 2, "requirements.past_due"),
                Listings.NONE,
                true));
        assertThat(PilotStages.next(pastDue)).satisfies(n -> {
            assertThat(n.state()).isEqualTo("blocked");
            assertThat(n.action()).isEqualTo("stripe_requirements");
        });
    }

    @Test
    void approvedAndPublished_isLiveOnlyOnceTheMarketOpens() {
        var hidden = business("seller", "active", false, null, null, true, "pilot");
        var before = PilotStages.checklist(new Inputs(pilot("seller", hidden), READY, Listings.of(4, 4, 4), false));
        assertThat(PilotStages.stage(before)).isEqualTo("approved");
        assertThat(PilotStages.next(before))
                .satisfies(n -> assertThat(n.action()).isEqualTo("market_launch"));

        var shown = business("seller", "active", false, null, null, true, null);
        var after = PilotStages.checklist(new Inputs(pilot("seller", shown), READY, Listings.of(4, 4, 4), true));
        assertThat(PilotStages.stage(after)).isEqualTo("live");
        assertThat(PilotStages.next(after)).isNull();
    }

    @Test
    void aKitchenWithoutItsSettings_isNotLiveYet() {
        var b = business("kitchen", "active", false, null, null, true, null);
        var steps = PilotStages.checklist(new Inputs(pilot("kitchen", b), READY, new Listings(2, 2, 2, false), true));
        assertThat(PilotStages.next(steps))
                .satisfies(n -> assertThat(n.action()).isEqualTo("set_up_kitchen"));
    }

    @Test
    void anApprovedBusinessWithoutAMarket_isBlocked() {
        var b = new Business(
                "Pho Mai",
                "seller",
                "active",
                "done",
                "AB",
                null,
                true,
                12,
                12,
                List.of(),
                "verified",
                false,
                0,
                false,
                null,
                null,
                null,
                AT,
                AT,
                true,
                null);
        var steps = PilotStages.checklist(new Inputs(pilot("seller", b), READY, Listings.of(1, 1, 1), true));
        assertThat(PilotStages.next(steps)).satisfies(n -> {
            assertThat(n.state()).isEqualTo("blocked");
            assertThat(n.action()).isEqualTo("no_market");
        });
    }
}
