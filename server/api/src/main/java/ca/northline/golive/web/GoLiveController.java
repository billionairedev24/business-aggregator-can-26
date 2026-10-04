package ca.northline.golive.web;

import ca.northline.golive.application.GoLiveChecklist;
import ca.northline.golive.application.GoLiveChecklist.Actor;
import ca.northline.golive.application.GoLiveChecklist.Checklist;
import ca.northline.golive.application.GoLiveSwitch;
import ca.northline.golive.domain.HypercareRotation;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's Go-live screen (S-118, docs/runbooks/go-live.md). Reading: admins, finance, merchant success, trust &
 * safety. Recording a manual gate: {@code attest} (admins, finance, merchant success). Switching and hypercare: admins
 * ({@code province}); the approver of a launch must be another admin than its requester.
 *
 * <pre>
 * GET  /api/v1/console/go-live                                         {items: [MarketSummary]}
 * GET  /api/v1/console/go-live/{marketId}                              Checklist
 * POST /api/v1/console/go-live/{marketId}/gates/{gate} {status, evidence, evidenceUrl?, source?}   409 gate_automatic
 * POST /api/v1/console/go-live/{marketId}/launch-requests {note?, overrideReason?}   409 not_pilot · request_pending · not_ready
 * POST /api/v1/console/go-live/{marketId}/launch-requests/{id}/approve {confirm}   409 same_person · request_closed · request_expired · not_ready
 * POST /api/v1/console/go-live/{marketId}/launch-requests/{id}/close {reason?}       withdrawn (requester) or rejected
 * POST /api/v1/console/go-live/{marketId}/rollback {reason, confirm}                 409 not_live
 * POST /api/v1/console/go-live/{marketId}/hypercare {startsOn?, primaries, secondaries, businessContacts}   409 not_live · hypercare_exists
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/go-live")
@RequiredArgsConstructor
@RequiresConsole(ConsoleScreen.GO_LIVE)
class GoLiveController {

    private final GoLiveChecklist checklist;
    private final GoLiveSwitch golive;

    /** @param source {@code console} (default) or {@code script} ({@code make go-live-check RECORD=1}) */
    record GateRequest(
            @NotBlank(message = GoLiveChecklist.STATUS) String status,
            @NotBlank(message = GoLiveChecklist.EVIDENCE) String evidence,
            @Nullable String evidenceUrl,
            @Nullable String source) {}

    record LaunchBody(@Nullable String note, @Nullable String overrideReason) {}

    record ApproveBody(
            @NotBlank(message = GoLiveSwitch.CONFIRM) String confirm) {}

    record CloseBody(@Nullable String reason) {}

    record RollbackBody(
            @NotBlank(message = GoLiveSwitch.ROLLBACK_REASON)
            String reason,

            @NotBlank(message = GoLiveSwitch.CONFIRM) String confirm) {}

    record HypercareBody(
            @Nullable LocalDate startsOn,
            @NotNull(message = HypercareRotation.PRIMARIES) List<String> primaries,

            @NotNull(message = HypercareRotation.SECONDARIES)
            List<String> secondaries,

            @NotNull(message = HypercareRotation.BUSINESS) List<String> businessContacts) {}

    @GetMapping
    ResponseEntity<ListResponse<GoLiveChecklist.MarketSummary>> markets() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ListResponse<>(checklist.markets()));
    }

    @GetMapping("/{marketId}")
    ResponseEntity<Checklist> checklist(@PathVariable String marketId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(checklist.checklist(marketId));
    }

    @PostMapping("/{marketId}/gates/{gate}")
    @RequiresConsole(value = ConsoleScreen.GO_LIVE, actions = ConsoleAction.ATTEST)
    Checklist record(
            @PathVariable String marketId,
            @PathVariable String gate,
            @Valid @RequestBody GateRequest body,
            CurrentStaff staff) {
        return checklist.record(
                marketId,
                gate,
                new GoLiveChecklist.Attestation(
                        body.status(),
                        body.evidence(),
                        body.evidenceUrl(),
                        body.source() == null ? "console" : body.source()),
                actor(staff));
    }

    @PostMapping("/{marketId}/launch-requests")
    @RequiresConsole(value = ConsoleScreen.GO_LIVE, actions = ConsoleAction.PROVINCE)
    Checklist request(@PathVariable String marketId, @Valid @RequestBody LaunchBody body, CurrentStaff staff) {
        return golive.request(marketId, body.note(), body.overrideReason(), actor(staff));
    }

    @PostMapping("/{marketId}/launch-requests/{requestId}/approve")
    @RequiresConsole(value = ConsoleScreen.GO_LIVE, actions = ConsoleAction.PROVINCE)
    Checklist approve(
            @PathVariable String marketId,
            @PathVariable String requestId,
            @Valid @RequestBody ApproveBody body,
            CurrentStaff staff) {
        return golive.approve(marketId, requestId, body.confirm(), actor(staff));
    }

    @PostMapping("/{marketId}/launch-requests/{requestId}/close")
    @RequiresConsole(value = ConsoleScreen.GO_LIVE, actions = ConsoleAction.PROVINCE)
    Checklist close(
            @PathVariable String marketId,
            @PathVariable String requestId,
            @Valid @RequestBody CloseBody body,
            CurrentStaff staff) {
        return golive.close(marketId, requestId, body.reason(), actor(staff));
    }

    @PostMapping("/{marketId}/rollback")
    @RequiresConsole(value = ConsoleScreen.GO_LIVE, actions = ConsoleAction.PROVINCE)
    Checklist rollback(@PathVariable String marketId, @Valid @RequestBody RollbackBody body, CurrentStaff staff) {
        return golive.rollback(marketId, body.reason(), body.confirm(), actor(staff));
    }

    @PostMapping("/{marketId}/hypercare")
    @RequiresConsole(value = ConsoleScreen.GO_LIVE, actions = ConsoleAction.PROVINCE)
    Checklist hypercare(@PathVariable String marketId, @Valid @RequestBody HypercareBody body, CurrentStaff staff) {
        return golive.startHypercare(
                marketId, body.startsOn(), body.primaries(), body.secondaries(), body.businessContacts(), actor(staff));
    }

    private static Actor actor(CurrentStaff staff) {
        return new Actor(staff.userId(), staff.roleCodes());
    }
}
