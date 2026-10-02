package ca.northline.privacy.web;

import ca.northline.privacy.application.PrivacyRequests.Actor;
import ca.northline.privacy.application.Retention;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import io.swagger.v3.oas.annotations.Operation;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — the retention report (S-107): every category of the Privacy Policy's retention schedule with its
 * period, legal basis, action, last run, rows affected, legal holds and next run; CSV export; runs and dry runs now.
 * Screen {@code privacy} (privacy officer, support lead, admin); running needs the {@code privacy} action. Each run is
 * in the audit log.
 *
 * <pre>
 * GET  /api/v1/console/retention          Report
 * GET  /api/v1/console/retention/export   text/csv, one category per line
 * POST /api/v1/console/retention/runs     {dryRun, category?}  → {items: [RunView]}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/retention")
@RequiredArgsConstructor
class RetentionController {

    private final Retention.Desk desk;

    /** @param category a catalogue code; absent = every category that has a job */
    record RunBody(boolean dryRun, @Nullable String category) {}

    @Operation(summary = "The retention schedule and what its jobs did")
    @GetMapping
    @RequiresConsole(ConsoleScreen.PRIVACY)
    ResponseEntity<Retention.Report> report(Locale locale) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(desk.report(locale));
    }

    @Operation(summary = "The retention report as CSV")
    @GetMapping(value = "/export", produces = "text/csv")
    @RequiresConsole(ConsoleScreen.PRIVACY)
    ResponseEntity<String> export(Locale locale) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"retention-report.csv\"")
                .cacheControl(CacheControl.noStore())
                .body(desk.csv(locale));
    }

    @Operation(summary = "Run or dry-run the retention jobs now (one category or all)")
    @PostMapping("/runs")
    @RequiresConsole(value = ConsoleScreen.PRIVACY, actions = ConsoleAction.PRIVACY)
    ListResponse<Retention.RunView> run(@RequestBody RunBody body, CurrentStaff staff) {
        return new ListResponse<>(desk.run(
                new Retention.Command(body.dryRun(), body.category()), new Actor(staff.userId(), staff.roleCodes())));
    }
}
