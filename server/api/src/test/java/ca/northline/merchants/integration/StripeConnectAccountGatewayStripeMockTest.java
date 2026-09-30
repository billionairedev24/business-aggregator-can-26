package ca.northline.merchants.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.merchants.application.ConnectAccountGateway.Kind;
import ca.northline.merchants.application.ConnectAccountGateway.State;
import ca.northline.shared.stripe.StripeClients;
import ca.northline.support.StripeMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Connect Express accounts against stripe-mock: creation, requirements → the compliance screen's lines, links. */
class StripeConnectAccountGatewayStripeMockTest {

    private final StripeMock.Recorder recorder = new StripeMock.Recorder();
    private final StripeConnectAccountGateway gateway = new StripeConnectAccountGateway(StripeMock.client(recorder));

    @AfterEach
    void everyMutatingCallHadAnIdempotencyKey_andThePinnedVersion() {
        assertThat(recorder.sent())
                .allSatisfy(s -> assertThat(s.version()).isEqualTo(StripeClients.PINNED_API_VERSION));
        assertThat(recorder.sent().stream().filter(StripeMock.Sent::mutating))
                .allSatisfy(s -> assertThat(s.idempotencyKeys())
                        .singleElement()
                        .satisfies(k -> assertThat(k).startsWith("nl1:")));
    }

    @Test
    void createsAnExpressAccount_keyedByTheMerchant() {
        var id = gateway.createExpressAccount("01J9ZD3V00000000000000PWM1");
        assertThat(id).startsWith("acct_");
        assertThat(recorder.sent().getLast().idempotencyKeys())
                .containsExactly("nl1:connect-account:01J9ZD3V00000000000000PWM1");
    }

    @Test
    void account_mapsRequirementsToTheComplianceLines() {
        // stripe-mock's account fixture: business_profile.*, external_account and tos_acceptance.* currently due
        var account = gateway.account("acct_1").orElseThrow();
        assertThat(account.id()).startsWith("acct_");
        assertThat(account.requirements())
                .extracting(r -> r.kind(), r -> r.state())
                .contains(
                        org.assertj.core.groups.Tuple.tuple(Kind.BUSINESS, State.DUE),
                        org.assertj.core.groups.Tuple.tuple(Kind.BANK, State.DUE),
                        org.assertj.core.groups.Tuple.tuple(Kind.IDENTITY, State.VERIFIED),
                        org.assertj.core.groups.Tuple.tuple(Kind.OWNERS, State.VERIFIED));
        assertThat(account.bankLabel()).contains("··");
    }

    @Test
    void requirementStrings_groupIntoTheFiveLines() {
        assertThat(StripeConnectAccountGateway.identity("individual.verification.document"))
                .isTrue();
        assertThat(StripeConnectAccountGateway.identity("representative.dob.day"))
                .isTrue();
        assertThat(StripeConnectAccountGateway.business("company.tax_id")).isTrue();
        assertThat(StripeConnectAccountGateway.business("company.owners_provided"))
                .isFalse();
        assertThat(StripeConnectAccountGateway.owners("company.owners_provided"))
                .isTrue();
        assertThat(StripeConnectAccountGateway.owners("company.directors_provided"))
                .isTrue();
        assertThat(StripeConnectAccountGateway.owners("owners.person_1.dob")).isTrue();
    }

    @Test
    void onboardingAndDashboardLinks() {
        assertThat(gateway.onboardingLink("acct_1", "https://studio.example/compliance", "https://studio.example/r"))
                .startsWith("http");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/account_links");
        assertThat(gateway.dashboardLink("acct_1")).startsWith("http");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/accounts/acct_1/login_links");
    }
}
