package ca.northline.payments.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.application.TaxGateway;
import ca.northline.payments.domain.CanadianTax.Province;
import ca.northline.shared.stripe.StripeClients;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import ca.northline.support.StripeMock;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The Stripe Tax adapter against stripe-mock, which validates every request against Stripe's OpenAPI spec for the
 * pinned version (it keeps no state and answers with fixtures, so amounts aren't checked here — the local fake and the
 * sync tests cover the arithmetic). Every mutating call must carry an {@code nl1:} Idempotency-Key and every call the
 * pinned {@code Stripe-Version}. Nothing here has run against a real Stripe account.
 */
class StripeTaxGatewayStripeMockTest {

    private final StripeMock.Recorder recorder = new StripeMock.Recorder();
    private final StripeTaxGateway gateway = new StripeTaxGateway(
            StripeMock.client(recorder),
            new TaxProperties(TaxProperties.Provider.STRIPE, "txcd_20030000", "txcd_99999999", "txcd_40060003"),
            Clock.systemUTC());

    private static final Map<String, String> IDS = Map.of(
            "northline_merchant_id", "PWM1",
            "northline_escrow_id", "01J9ZD3V00000000000000ESC1",
            "northline_reference", "sale_01J9ZD3V00000000000000ESC1");

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
    void calculateRecordReverseAndRead() {
        var calculated = gateway.calculate(new TaxGateway.Calculate(
                "sale_01J9ZD3V00000000000000ESC1",
                EscrowKind.SERVICE,
                Province.BC,
                "V6B 1A1",
                10_000,
                StripeIdempotencyKeys.of("tax-calculation", "sale_01J9ZD3V00000000000000ESC1")));
        assertThat(calculated.calculation()).startsWith("taxcalc_");
        assertThat(calculated.expiresAt()).isNotNull();
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/tax/calculations");

        var sale = gateway.record(
                calculated.calculation(),
                "sale_01J9ZD3V00000000000000ESC1",
                IDS,
                StripeIdempotencyKeys.of("tax-transaction", "sale_01J9ZD3V00000000000000ESC1"));
        assertThat(sale.transaction()).startsWith("tax_");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/tax/transactions/create_from_calculation");

        var reversal = gateway.reverse(
                sale.transaction(),
                "refund_01J9ZD3V00000000000000REF1",
                5_600,
                IDS,
                StripeIdempotencyKeys.of("tax-reversal", "refund_01J9ZD3V00000000000000REF1"));
        assertThat(reversal.transaction()).startsWith("tax_");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/tax/transactions/create_reversal");

        assertThat(gateway.taxOf(sale.transaction())).isNotNegative();
        assertThat(recorder.sent().getLast().path()).endsWith("/line_items");
    }

    @Test
    void everyKindHasItsTaxCode() {
        for (var kind : EscrowKind.values()) {
            var calculated = gateway.calculate(new TaxGateway.Calculate(
                    "calculation_" + kind.code(),
                    kind,
                    Province.AB,
                    null,
                    2_500,
                    StripeIdempotencyKeys.of("tax-calculation", kind.code())));
            assertThat(calculated.calculation()).startsWith("taxcalc_");
        }
    }
}
