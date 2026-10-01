package ca.northline.studio.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.RequiresMerchant;
import ca.northline.studio.application.CalendarCells;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-74: {@code GET /api/v1/merchants/{merchantId}/calendar-cells?from=2026-10-05&days=7&durationMin=60} — the
 * appointments calendar's "Open slot" and "Held for quote" cells for {@code days} days from {@code from} (dates in the
 * business's time zone). Any member (VIEW): the calendar is shared by the team.
 */
@RestController
@RequiredArgsConstructor
class CalendarCellsController {

    static final String DAYS_RANGE = "Pick 1 to 31 days.";
    static final String DURATION_RANGE = "Choose a duration between 15 minutes and 12 hours.";

    private final CalendarCells cells;

    @GetMapping("/api/v1/merchants/{merchantId}/calendar-cells")
    @RequiresMerchant(VIEW)
    CalendarCells.Cells cells(
            @PathVariable String merchantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "60") int durationMin) {
        if (days < 1 || days > 31) {
            throw RuleViolation.of("days", "range", DAYS_RANGE);
        }
        if (durationMin < 15 || durationMin > 720) {
            throw RuleViolation.of("durationMin", "range", DURATION_RANGE);
        }
        return cells.of(merchantId, from, days, durationMin);
    }
}
