package ca.northline.restricted.web;

import ca.northline.region.api.AgeRules;
import ca.northline.region.api.AgeRules.AgeRule;
import ca.northline.region.api.Regions;
import ca.northline.restricted.api.AgeChecksReport;
import ca.northline.restricted.api.AgeChecksReport.Report;
import ca.northline.shared.ListResponse;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.RequiresConsole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — age-restricted sales for trust &amp; safety (Listing vetting › Age checks; admin and trust &amp;
 * safety open it). Counts and codes only: no customer, no ID data.
 *
 * <pre>
 * GET /api/v1/console/vetting/age-checks[?from=&amp;to=&amp;province=]   Report (default: the last 30 days)
 * GET /api/v1/console/vetting/age-rules                         {items: [AgeRule]} the region model's rules
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/vetting")
@RequiredArgsConstructor
class AgeChecksReportController {

    static final String PERIOD = "Choose a period of at most a year, the start before the end.";
    static final String PROVINCE = "Choose a province from the list.";

    private final AgeChecksReport report;
    private final AgeRules rules;
    private final Regions regions;
    private final Clock clock;

    @GetMapping("/age-checks")
    @RequiresConsole(ConsoleScreen.VETTING)
    Report report(
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable String province) {
        var end = instant(to, clock.instant());
        var start = instant(from, end.minus(Duration.ofDays(30)));
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(366)) > 0) {
            throw RuleViolation.of("from", "range", PERIOD);
        }
        var p = province == null || province.isBlank() ? null : province.strip().toUpperCase(java.util.Locale.ROOT);
        if (p != null && regions.province(p).isEmpty()) {
            throw RuleViolation.of("province", "unknown", PROVINCE);
        }
        return report.report(start, end, p);
    }

    @GetMapping("/age-rules")
    @RequiresConsole(ConsoleScreen.VETTING)
    ListResponse<AgeRule> rules() {
        return new ListResponse<>(rules.all());
    }

    private static Instant instant(@Nullable String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw RuleViolation.of("from", "range", PERIOD);
        }
    }
}
