package ca.northline.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** S-85: export cells can't start a spreadsheet formula; amounts stay numbers; commas and quotes are quoted. */
class ReconciliationCsvTest {

    @Test
    void formulasAreNeutralisedAndNumbersKept() {
        assertThat(StripeReconciliationService.csv("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(StripeReconciliationService.csv("+1+2")).isEqualTo("'+1+2");
        assertThat(StripeReconciliationService.csv("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(StripeReconciliationService.csv("-1250")).isEqualTo("-1250");
        assertThat(StripeReconciliationService.csv("Late refund, booked")).isEqualTo("\"Late refund, booked\"");
        assertThat(StripeReconciliationService.csv("ch_123")).isEqualTo("ch_123");
    }
}
