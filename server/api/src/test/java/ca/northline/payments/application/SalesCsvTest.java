package ca.northline.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * S-104: the Studio's sales export carries names other people typed (customers, listings). A cell a spreadsheet would
 * evaluate as a formula is neutralised with a leading quote mark; amounts, refunds and dates stay numbers.
 */
class SalesCsvTest {

    @Test
    void formulas_areNeutralised() {
        var csv = new SalesReportService.Csv("Customer", "Gross");
        csv.row("=HYPERLINK(\"https://evil.example\",\"Click\")", "12.00");
        csv.row("+1+cmd|' /C calc'!A0", "-3.50");
        csv.row("@SUM(A1:A9)", "0.00");
        csv.row("-2+3", ".75");
        csv.row("\tTabbed", "1");

        assertThat(csv.toString().split("\r\n"))
                .containsExactly(
                        "Customer,Gross",
                        "\"'=HYPERLINK(\"\"https://evil.example\"\",\"\"Click\"\")\",12.00",
                        "'+1+cmd|' /C calc'!A0,-3.50",
                        "'@SUM(A1:A9),0.00",
                        "'-2+3,.75",
                        "'\tTabbed,1");
    }

    @Test
    void ordinaryText_isUnchanged() {
        assertThat(SalesReportService.Csv.guardFormula("Amara Osei")).isEqualTo("Amara Osei");
        assertThat(SalesReportService.Csv.guardFormula("-12.40")).isEqualTo("-12.40");
        assertThat(SalesReportService.Csv.guardFormula("- dash note")).isEqualTo("'- dash note");
        assertThat(SalesReportService.Csv.guardFormula("")).isEmpty();
    }
}
