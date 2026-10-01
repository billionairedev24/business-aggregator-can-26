package ca.northline.payments.web;

import ca.northline.payments.application.ReconcileStripe;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe ↔ ledger reconciliation (S-85, design 03 finance "Reconciliation · daily"): admin and finance read it; running
 * a day again and resolving a difference need {@code payouts}. Exports and every change are audited.
 *
 * <pre>
 * GET  /api/v1/console/payments/reconciliation?from=&amp;to=        {items: [Day]} (default: the last 14 days)
 * GET  /api/v1/console/payments/reconciliation/{day}              {day, items: [Item]}   404
 * GET  /api/v1/console/payments/reconciliation/export?from=&amp;to=  text/csv (the days and their differences)
 * GET  /api/v1/console/payments/reconciliation/ledger-export?from=&amp;to=   text/csv (every ledger entry)
 * POST /api/v1/console/payments/reconciliation/run {day}          Day   422 day (not ended)
 * POST /api/v1/console/payments/reconciliation/{day}/resolve {note}   Day   409 not_mismatched
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/payments/reconciliation")
@RequiredArgsConstructor
@RequiresConsole(ConsoleScreen.FINANCE)
class ReconciliationController {

    static final String DAY = "Choose a day (YYYY-MM-DD).";
    static final String RANGE = "Choose at most 92 days, the first before the last.";

    private final ReconcileStripe reconcile;
    private final java.time.Clock clock;
    private final ca.northline.payments.application.BusinessTime time;

    record RunRequest(@NotBlank(message = DAY) String day) {}

    record ResolveRequest(
            @NotBlank(message = ReconcileStripe.NOTE_REQUIRED) @Size(max = 500, message = ReconcileStripe.NOTE_LENGTH)
            String note) {}

    record Days(List<ReconcileStripe.Day> items) {}

    @GetMapping
    ResponseEntity<Days> days(
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to) {
        var range = range(from, to);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new Days(reconcile.days(range[0], range[1])));
    }

    @GetMapping("/{day}")
    ReconcileStripe.DayDetail day(@PathVariable String day) {
        return reconcile.day(parse(day, "day"));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    ResponseEntity<String> export(
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            CurrentStaff staff) {
        var range = range(from, to);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"reconciliation-%s-%s.csv\"".formatted(range[0], range[1]))
                .cacheControl(CacheControl.noStore())
                .body(reconcile.export(range[0], range[1], actor(staff)));
    }

    /** "Export to accounting": the ledger of the range as CSV. */
    @GetMapping(value = "/ledger-export", produces = "text/csv")
    ResponseEntity<String> ledger(
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            CurrentStaff staff) {
        var range = range(from, to);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ledger-%s-%s.csv\"".formatted(range[0], range[1]))
                .cacheControl(CacheControl.noStore())
                .body(reconcile.exportLedger(range[0], range[1], actor(staff)));
    }

    @PostMapping("/run")
    @RequiresConsole(value = ConsoleScreen.FINANCE, actions = ConsoleAction.PAYOUTS)
    ReconcileStripe.Day run(@Valid @RequestBody RunRequest body, CurrentStaff staff) {
        return reconcile.run(parse(body.day(), "day"), actor(staff));
    }

    @PostMapping("/{day}/resolve")
    @RequiresConsole(value = ConsoleScreen.FINANCE, actions = ConsoleAction.PAYOUTS)
    ReconcileStripe.Day resolve(@PathVariable String day, @Valid @RequestBody ResolveRequest body, CurrentStaff staff) {
        return reconcile.resolve(parse(day, "day"), body.note(), actor(staff));
    }

    private LocalDate[] range(@Nullable String from, @Nullable String to) {
        var end = to == null || to.isBlank() ? LocalDate.now(clock.withZone(time.platform())) : parse(to, "to");
        var start = from == null || from.isBlank() ? end.minusDays(13) : parse(from, "from");
        if (start.isAfter(end) || start.plusDays(92).isBefore(end)) {
            throw RuleViolation.of("from", "range", RANGE);
        }
        return new LocalDate[] {start, end};
    }

    private static LocalDate parse(String value, String field) {
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw RuleViolation.of(field, "format", DAY);
        }
    }

    private static ReconcileStripe.Actor actor(CurrentStaff staff) {
        return new ReconcileStripe.Actor(staff.userId(), staff.roleCodes());
    }
}
