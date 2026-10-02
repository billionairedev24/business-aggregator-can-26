package ca.northline.console.web;

import ca.northline.console.application.OncallExport;
import ca.northline.console.application.OncallExport.Entry;
import ca.northline.console.application.OncallExport.Export;
import io.swagger.v3.oas.annotations.Operation;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-113: the on-call rota for the paging side (docs/runbooks/alerting.md § The on-call rota).
 *
 * <pre>
 * GET /api/v1/ops/oncall       application/json  {asOf, now: [Entry], shifts: [Entry]}
 * GET /api/v1/ops/oncall.ics   text/calendar     one VEVENT per shift, SUMMARY = the person's email (Grafana OnCall maps
 *                                                it to its user), DESCRIPTION = the duty
 *   Authorization: Bearer &lt;ONCALL_EXPORT_TOKEN&gt;   — or ?token=… for calendar clients that can't send a header
 *   401 without the right token; 404 when no token is configured (the export is off)
 * </pre>
 *
 * No user session or staff token: the callers are machines. The token is the only secret; rotate it like the others.
 */
@RestController
@RequiredArgsConstructor
class OncallExportController {

    static final MediaType CALENDAR = new MediaType("text", "calendar", StandardCharsets.UTF_8);
    private static final DateTimeFormatter ICS_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final OncallExport export;

    @GetMapping(path = "/api/v1/ops/oncall", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Who is on call (for paging tools)", description = """
                    The rota from 12 h ago to 8 days ahead and who is on call now, with each person's email. \
                    Authorization: Bearer <ONCALL_EXPORT_TOKEN>. 404 when the export is not configured.""")
    ResponseEntity<?> json(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable String authorization,
            @RequestParam(required = false) @Nullable String token) {
        var refused = refused(authorization, token);
        return refused != null ? refused : ResponseEntity.ok(export.export());
    }

    @GetMapping(path = "/api/v1/ops/oncall.ics")
    @Operation(summary = "The on-call rota as an iCalendar feed", description = """
                    One event per shift; SUMMARY is the person's email. Authorization: Bearer <ONCALL_EXPORT_TOKEN>, \
                    or ?token= for calendar clients. 404 when the export is not configured.""")
    ResponseEntity<?> calendar(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable String authorization,
            @RequestParam(required = false) @Nullable String token) {
        var refused = refused(authorization, token);
        return refused != null
                ? refused
                : ResponseEntity.ok().contentType(CALENDAR).body(ics(export.export()));
    }

    private @Nullable ResponseEntity<?> refused(@Nullable String authorization, @Nullable String token) {
        if (!export.enabled()) {
            return problem(HttpStatus.NOT_FOUND, "The on-call export is not configured.");
        }
        var presented = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim()
                : token;
        return export.accepts(presented) ? null : problem(HttpStatus.UNAUTHORIZED, "Send the on-call export token.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    /** RFC 5545, CRLF line ends, UTC times; texts escaped. */
    static String ics(Export export) {
        var out = new StringBuilder();
        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//Northline//On-call rota//EN");
        line(out, "CALSCALE:GREGORIAN");
        line(out, "X-WR-CALNAME:Northline on-call");
        for (Entry e : export.shifts()) {
            line(out, "BEGIN:VEVENT");
            line(out, "UID:" + e.shiftId() + "@oncall.northline");
            line(out, "DTSTAMP:" + ICS_TIME.format(export.asOf()));
            line(out, "DTSTART:" + ICS_TIME.format(e.startsAt()));
            line(out, "DTEND:" + ICS_TIME.format(e.endsAt()));
            line(out, "SUMMARY:" + text(e.email() != null ? e.email() : e.name()));
            line(out, "DESCRIPTION:" + text(e.name() + " · " + e.duty()));
            line(out, "END:VEVENT");
        }
        line(out, "END:VCALENDAR");
        return out.toString();
    }

    private static void line(StringBuilder out, String line) {
        out.append(line).append("\r\n");
    }

    private static String text(String value) {
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r", "")
                .replace("\n", "\\n");
    }
}
