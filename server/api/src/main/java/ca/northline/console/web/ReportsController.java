package ca.northline.console.web;

import ca.northline.console.application.ViewReports;
import ca.northline.console.application.ViewReports.Report;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.RequiresConsole;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — reports &amp; analytics (S-95, design 03 {@code reports}; admin, finance, analyst; read only).
 *
 * <pre>
 * GET /api/v1/console/reports[?province=AB]   Report (aggregates only; counts under 5 withheld)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/reports")
@RequiredArgsConstructor
class ReportsController {

    private final ViewReports reports;

    @GetMapping
    @RequiresConsole(ConsoleScreen.REPORTS)
    Report report(@RequestParam(required = false) @Nullable String province) {
        return reports.report(province == null || province.isBlank() ? null : province);
    }
}
