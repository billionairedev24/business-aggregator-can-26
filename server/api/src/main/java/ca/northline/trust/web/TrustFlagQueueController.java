package ca.northline.trust.web;

import ca.northline.shared.ListResponse;
import ca.northline.shared.PlaceFilter;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import ca.northline.shared.security.StaffAccessDenied;
import ca.northline.trust.application.TrustFlagQueue;
import ca.northline.trust.application.TrustFlagQueue.FlagView;
import ca.northline.trust.application.TrustRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — trust &amp; safety queue (S-133): every flag with its explanation (AI-suggested ones marked
 * {@code source: ai}), and the staff decision. {@code /api/v1/console/**} needs role {@code STAFF} and a second factor;
 * S-90: the trust screen (admin, trust &amp; safety), deciding needs {@code decide}.
 *
 * <pre>
 * GET  /api/v1/console/trust/flags[?state=open|dismissed|actioned][&amp;source=ai|rules][&amp;limit=50]   {items: [FlagView]}
 * POST /api/v1/console/trust/flags/{id}/decision {decision: dismissed|actioned, note?}             FlagView
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/trust/flags")
@RequiredArgsConstructor
class TrustFlagQueueController {

    private final TrustFlagQueue queue;
    private final PlaceFilter places;

    record DecisionRequest(
            @NotBlank(message = TrustFlagQueue.DECISION_REQUIRED)
            @Pattern(regexp = "dismissed|actioned", message = TrustFlagQueue.DECISION_REQUIRED)
            String decision,

            @Nullable @Size(max = 500, message = "At most 500 characters.")
            String note) {}

    @GetMapping
    @RequiresConsole(ConsoleScreen.TRUST)
    ListResponse<FlagView> list(
            @RequestParam(defaultValue = "open") @Pattern(regexp = "open|dismissed|actioned|all") String state,
            @RequestParam(required = false) @Nullable @Pattern(regexp = "ai|rules") String source,
            @RequestParam(defaultValue = "50") int limit) {
        return new ListResponse<>(queue.list("all".equals(state) ? null : state, source, limit));
    }

    record ActionRequest(
            @NotBlank(message = TrustRules.ACTION_REQUIRED)
            @Pattern(regexp = "warn|coach|confirm|suspend_listings|escalate|hide_review", message = TrustRules.ACTION_REQUIRED)
            String action,

            @Nullable @Size(max = 500, message = "At most 500 characters.")
            String note) {}

    /** S-93: open flags of the businesses in a province / market (with their names) and those decided this week. */
    @GetMapping("/queue")
    @RequiresConsole(ConsoleScreen.TRUST)
    ListResponse<FlagView> queue(
            @RequestParam(required = false) @Nullable String province,
            @RequestParam(required = false) @Nullable String market) {
        return new ListResponse<>(queue.queue(places.resolve(province, market).scope(), 200));
    }

    /**
     * S-93: act on a flag (design: Warn, Start coaching, Confirm, Suspend listing rights, Escalate to ops); suspending
     * listing rights also needs the {@code suspend} action.
     */
    @PostMapping("/{id}/action")
    @RequiresConsole(value = ConsoleScreen.TRUST, actions = ConsoleAction.DECIDE)
    FlagView act(@PathVariable String id, @Valid @RequestBody ActionRequest body, CurrentStaff staff) {
        var action = TrustRules.FlagAction.valueOf(body.action().toUpperCase(Locale.ROOT));
        if (action == TrustRules.FlagAction.SUSPEND_LISTINGS
                && staff.active().stream().noneMatch(r -> r.allows(ConsoleAction.SUSPEND))) {
            throw new StaffAccessDenied(StaffAccessDenied.Reason.INSUFFICIENT_ROLE, StaffAccessDenied.ACTION_MESSAGE);
        }
        return queue.act(id, action, staff.userId(), staff.roleCodes(), body.note());
    }

    @PostMapping("/{id}/decision")
    @RequiresConsole(value = ConsoleScreen.TRUST, actions = ConsoleAction.DECIDE)
    FlagView decide(@PathVariable String id, @Valid @RequestBody DecisionRequest body, CurrentStaff staff) {
        return queue.decide(id, body.decision(), staff.userId(), staff.roleCodes(), body.note());
    }
}
