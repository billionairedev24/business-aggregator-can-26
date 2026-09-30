package ca.northline.payments.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.payments.application.BankLinking;
import ca.northline.shared.stripe.StripeClients;
import ca.northline.support.StripeMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * S-24: the Financial Connections adapter against stripe-mock, which validates every request against Stripe's OpenAPI
 * spec for the pinned version and answers with fixtures (so its Financial Connections account is held by a fixture
 * customer, not our connected account — which exercises the ownership check). Every mutating call carries an
 * {@code nl1:} Idempotency-Key and every call the pinned {@code Stripe-Version}; the account number never appears in a
 * key. Nothing here has run against a real Stripe account.
 */
class StripeBankLinkingStripeMockTest {

    private final StripeMock.Recorder recorder = new StripeMock.Recorder();
    private final StripeBankLinking linking = new StripeBankLinking(StripeMock.client(recorder), "pk_test_fake");

    @AfterEach
    void everyMutatingCallHadAnIdempotencyKey_andThePinnedVersion() {
        var sent = recorder.sent();
        assertThat(sent).isNotEmpty();
        assertThat(sent).allSatisfy(s -> assertThat(s.version()).isEqualTo(StripeClients.PINNED_API_VERSION));
        assertThat(sent.stream().filter(StripeMock.Sent::mutating))
                .allSatisfy(s -> assertThat(s.idempotencyKeys())
                        .as("%s %s", s.method(), s.path())
                        .singleElement()
                        .satisfies(k -> assertThat(k).startsWith("nl1:")));
    }

    @Test
    void session_forTheConnectedAccount() {
        var session = linking.start("acct_1");
        assertThat(session.mode()).isEqualTo("stripe");
        assertThat(session.clientSecret()).isNotBlank();
        assertThat(session.publishableKey()).isEqualTo("pk_test_fake");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/financial_connections/sessions");
    }

    @Test
    void tokenBecomesAnExternalAccount_keepingNameAndLast4Only() {
        var linked = linking.link("acct_1", "btok_123", null);
        assertThat(linked.externalRef()).startsWith("ba_");
        assertThat(linked.last4()).hasSize(4);
        assertThat(linked.institutionNumber()).isNull();
        assertThat(linked.transitNumber()).isNull();
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/accounts/acct_1/external_accounts");
    }

    @Test
    void aFinancialConnectionsAccountOfSomeoneElse_isRefused() {
        assertThatThrownBy(() -> linking.link("acct_1", "btok_123", "fca_123"))
                .isInstanceOf(BankLinking.NotLinkable.class);
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/financial_connections/accounts/fca_123");
        assertThat(recorder.sent()).noneMatch(s -> s.path().endsWith("/external_accounts"));
    }

    @Test
    void typedDetails_areTokenizedThenAttached_withoutTheNumberInAnyKey() {
        var manual = linking.manual("acct_1", "004", "12345", "1234567", "Prairie Wrench Mobile Mechanics Ltd.");
        assertThat(manual.externalRef()).startsWith("ba_");
        assertThat(manual.last4()).hasSize(4);
        assertThat(recorder.sent()).extracting(StripeMock.Sent::path).contains("/v1/tokens");
        assertThat(recorder.sent())
                .allSatisfy(
                        s -> assertThat(String.join(",", s.idempotencyKeys())).doesNotContain("1234567"));
    }
}
