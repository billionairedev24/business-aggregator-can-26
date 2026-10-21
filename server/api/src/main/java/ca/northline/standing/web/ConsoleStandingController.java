package ca.northline.standing.web;

import ca.northline.shared.ListResponse;
import ca.northline.shared.NotFound;
import ca.northline.shared.PlaceFilter;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import ca.northline.standing.application.StandingUseCases.AppealDesk;
import ca.northline.standing.application.StandingUseCases.AppealDetail;
import ca.northline.standing.application.StandingUseCases.BusinessStanding;
import ca.northline.standing.application.StandingUseCases.CoachingView;
import ca.northline.standing.application.StandingUseCases.ConsequenceView;
import ca.northline.standing.application.StandingUseCases.FileAppeal;
import ca.northline.standing.application.StandingUseCases.ManageStanding;
import ca.northline.standing.application.StandingUseCases.QueueItem;
import ca.northline.standing.application.StandingUseCases.ViewStanding;
import ca.northline.standing.domain.AppealRules;
import ca.northline.standing.domain.CoachingRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — appeals and standing (S-156). The appeals queue lives under Trust &amp; safety (screen
 * {@code trust}; taking and deciding need {@code decide}: admin, trust &amp; safety); a business's standing is on its
 * seller detail (screen {@code sellers}; lifting a consequence needs {@code suspend}, coaching {@code decide}). Every
 * change is audited; reasons go to the business.
 *
 * <pre>
 * GET  /api/v1/console/appeals[?province=&amp;market=]              {items: [QueueItem]}
 * GET  /api/v1/console/appeals/{id}                                AppealDetail
 * GET  /api/v1/console/appeals/{id}/files/{fileId}                 the file
 * POST /api/v1/console/appeals/{id}/assign                         AppealDetail
 * POST /api/v1/console/appeals/{id}/decision {outcome: grant|deny, reason}   AppealDetail · 409 appeal_decided · appeal_self
 * GET  /api/v1/console/standing/{businessId}                       BusinessStanding
 * POST /api/v1/console/standing/consequences/{id}/lift {reason}    Consequence · 409 consequence_lifted
 * POST /api/v1/console/standing/{businessId}/coaching {title, note?, dueOn?, tasks[]}   201 Coaching · 409 coaching_active
 * POST /api/v1/console/standing/coaching/{planId}/close {completed, reason?}            Coaching · 409 plan_closed
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console")
@RequiredArgsConstructor
class ConsoleStandingController {

    private final PlaceFilter places;
    private final AppealDesk desk;
    private final FileAppeal files;
    private final ViewStanding view;
    private final ManageStanding manage;

    record DecisionRequest(
            @NotBlank(message = AppealRules.OUTCOME_REQUIRED)
            @Pattern(regexp = "grant|deny", message = AppealRules.OUTCOME_REQUIRED)
            String outcome,

            @NotBlank(message = AppealRules.REASON_REQUIRED)
            @Size(max = AppealRules.REASON_MAX, message = AppealRules.REASON_TOO_LONG)
            String reason) {}

    record LiftRequest(
            @NotBlank(message = AppealRules.REASON_REQUIRED)
            @Size(max = AppealRules.REASON_MAX, message = AppealRules.REASON_TOO_LONG)
            String reason) {}

    record CoachingRequest(
            @Nullable String title,
            @Nullable String note,
            @Nullable LocalDate dueOn,
            @Nullable List<String> tasks) {}

    record CloseRequest(
            @NotNull(message = CLOSE_REQUIRED) Boolean completed,

            @Nullable @Size(max = CoachingRules.NOTE_MAX, message = AppealRules.REASON_TOO_LONG)
            String reason) {}

    static final String CLOSE_REQUIRED = "Choose completed or cancelled.";

    @GetMapping("/appeals")
    @RequiresConsole(ConsoleScreen.TRUST)
    ListResponse<QueueItem> queue(
            @RequestParam(required = false) @Nullable String province,
            @RequestParam(required = false) @Nullable String market) {
        return new ListResponse<>(desk.queue(places.resolve(province, market).scope()));
    }

    @GetMapping("/appeals/{appealId}")
    @RequiresConsole(ConsoleScreen.TRUST)
    AppealDetail detail(@PathVariable String appealId) {
        return desk.detail(appealId).orElseThrow(() -> new NotFound("appeal", appealId));
    }

    @GetMapping("/appeals/{appealId}/files/{fileId}")
    @RequiresConsole(ConsoleScreen.TRUST)
    ResponseEntity<byte[]> file(@PathVariable String appealId, @PathVariable String fileId) {
        var f = files.file(null, appealId, fileId).orElseThrow(() -> new NotFound("file", fileId));
        return StudioStandingController.Files.response(f.bytes(), f.contentType(), f.name());
    }

    @PostMapping("/appeals/{appealId}/assign")
    @RequiresConsole(value = ConsoleScreen.TRUST, actions = ConsoleAction.DECIDE)
    AppealDetail assign(@PathVariable String appealId, CurrentStaff staff) {
        return desk.assign(appealId, staff.userId(), staff.roleCodes());
    }

    @PostMapping("/appeals/{appealId}/decision")
    @RequiresConsole(value = ConsoleScreen.TRUST, actions = ConsoleAction.DECIDE)
    AppealDetail decide(@PathVariable String appealId, @Valid @RequestBody DecisionRequest body, CurrentStaff staff) {
        return desk.decide(appealId, "grant".equals(body.outcome()), body.reason(), staff.userId(), staff.roleCodes());
    }

    @GetMapping("/standing/{businessId}")
    @RequiresConsole(ConsoleScreen.SELLERS)
    BusinessStanding standing(@PathVariable String businessId) {
        return view.business(businessId);
    }

    @PostMapping("/standing/consequences/{consequenceId}/lift")
    @RequiresConsole(value = ConsoleScreen.SELLERS, actions = ConsoleAction.SUSPEND)
    ConsequenceView lift(@PathVariable String consequenceId, @Valid @RequestBody LiftRequest body, CurrentStaff staff) {
        return manage.lift(consequenceId, body.reason(), staff.userId(), staff.roleCodes());
    }

    @PostMapping("/standing/{businessId}/coaching")
    @RequiresConsole(value = ConsoleScreen.SELLERS, actions = ConsoleAction.DECIDE)
    ResponseEntity<CoachingView> coaching(
            @PathVariable String businessId, @RequestBody CoachingRequest body, CurrentStaff staff) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(manage.startCoaching(
                        businessId,
                        body.title(),
                        body.note(),
                        body.dueOn(),
                        body.tasks(),
                        staff.userId(),
                        staff.roleCodes()));
    }

    @PostMapping("/standing/coaching/{planId}/close")
    @RequiresConsole(value = ConsoleScreen.SELLERS, actions = ConsoleAction.DECIDE)
    CoachingView close(@PathVariable String planId, @Valid @RequestBody CloseRequest body, CurrentStaff staff) {
        return manage.closeCoaching(planId, body.completed(), body.reason(), staff.userId(), staff.roleCodes());
    }
}
