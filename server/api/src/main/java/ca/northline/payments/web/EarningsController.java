package ca.northline.payments.web;

import static ca.northline.shared.security.MerchantPermission.FINANCE_READ;

import ca.northline.payments.application.BusinessTime;
import ca.northline.payments.application.ViewEarnings;
import ca.northline.payments.application.ViewSalesReport;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.RequiresMerchant;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Earnings and Sales reports (owner + bookkeeper):
 *
 * <pre>
 * GET /api/v1/merchants/{merchantId}/earnings                          headline + KPIs
 * GET /api/v1/merchants/{merchantId}/earnings/ledger?limit=            ledger table
 * GET /api/v1/merchants/{merchantId}/reports?period=30d|90d|12mo       report
 * GET /api/v1/merchants/{merchantId}/reports/export.csv?period=        Export CSV
 * GET /api/v1/merchants/{merchantId}/reports/gst-summary.csv?year=     Tax summary (GST)
 * GET /api/v1/merchants/{merchantId}/reports/annual-statement.csv?year=
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
@RequiredArgsConstructor
class EarningsController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ViewEarnings earnings;
    private final ViewSalesReport reports;
    private final PaymentsWebMapper mapper;
    private final Clock clock;
    private final BusinessTime time;

    @GetMapping("/earnings")
    @RequiresMerchant(FINANCE_READ)
    EarningsResponses.Overview overview(@PathVariable String merchantId) {
        return mapper.toResponse(earnings.overview(merchantId));
    }

    @GetMapping("/earnings/ledger")
    @RequiresMerchant(FINANCE_READ)
    ListResponse<EarningsResponses.LedgerLine> ledger(
            @PathVariable String merchantId, @RequestParam(defaultValue = "500") int limit) {
        return new ListResponse<>(mapper.toLedger(earnings.ledger(merchantId, limit)));
    }

    @GetMapping("/reports")
    @RequiresMerchant(FINANCE_READ)
    EarningsResponses.Report report(
            @PathVariable String merchantId, @RequestParam(defaultValue = "90d") String period) {
        return mapper.toResponse(reports.report(merchantId, ViewSalesReport.Period.of(period)));
    }

    @GetMapping("/reports/export.csv")
    @RequiresMerchant(FINANCE_READ)
    ResponseEntity<String> export(@PathVariable String merchantId, @RequestParam(defaultValue = "90d") String period) {
        var p = ViewSalesReport.Period.of(period);
        return csv("northline-sales-" + p.code() + "-" + today(merchantId) + ".csv", reports.exportCsv(merchantId, p));
    }

    @GetMapping("/reports/gst-summary.csv")
    @RequiresMerchant(FINANCE_READ)
    ResponseEntity<String> gst(@PathVariable String merchantId, @RequestParam @Nullable Integer year) {
        var y = year == null ? today(merchantId).getYear() : year;
        return csv("northline-gst-summary-" + y + ".csv", reports.gstSummaryCsv(merchantId, y));
    }

    @GetMapping("/reports/annual-statement.csv")
    @RequiresMerchant(FINANCE_READ)
    ResponseEntity<String> annual(@PathVariable String merchantId, @RequestParam @Nullable Integer year) {
        var y = year == null ? today(merchantId).getYear() - 1 : year;
        return csv("northline-annual-statement-" + y + ".csv", reports.annualStatementCsv(merchantId, y));
    }

    private LocalDate today(String merchantId) {
        return LocalDate.ofInstant(clock.instant(), time.of(merchantId));
    }

    private static ResponseEntity<String> csv(String filename, String body) {
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename)
                                .build()
                                .toString())
                .body(body);
    }
}
