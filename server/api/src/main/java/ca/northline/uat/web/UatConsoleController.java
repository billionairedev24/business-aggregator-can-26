package ca.northline.uat.web;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import ca.northline.uat.api.UatReadiness.GoNoGoReport;
import ca.northline.uat.application.UatReports;
import ca.northline.uat.application.UatTriage;
import ca.northline.uat.application.UatTriage.Actor;
import ca.northline.uat.application.UatTriage.FeedbackDetail;
import ca.northline.uat.application.UatTriage.Filter;
import ca.northline.uat.application.UatTriage.Move;
import ca.northline.uat.application.UatTriage.NewParticipant;
import ca.northline.uat.application.UatTriage.NewSignoff;
import ca.northline.uat.application.UatTriage.Owner;
import ca.northline.uat.application.UatTriage.ParticipantView;
import ca.northline.uat.application.UatTriage.QueueItem;
import ca.northline.uat.application.UatTriage.ScriptView;
import ca.northline.uat.domain.FeedbackState;
import ca.northline.uat.web.UatBodies.MoveBody;
import ca.northline.uat.web.UatBodies.OwnerBody;
import ca.northline.uat.web.UatBodies.ParticipantBody;
import ca.northline.uat.web.UatBodies.SignoffBody;
import ca.northline.uat.web.UatBodies.TrackerBody;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — UAT (S-121): the pilot feedback queue and its triage, the participants and their sign-offs, the
 * go/no-go report. Screen {@code uat} (support, support lead, admin); every change needs the {@code uat} action and is
 * in the audit log.
 *
 * <pre>
 * GET  /api/v1/console/uat/feedback[?state=open|all|&lt;state&gt;][&amp;blocking=][&amp;persona=][&amp;app=]  {items}
 * GET  /api/v1/console/uat/feedback/export[?…]               text/csv
 * GET  /api/v1/console/uat/feedback/{id}                     FeedbackDetail
 * GET  /api/v1/console/uat/feedback/{id}/screenshot          the image
 * POST /api/v1/console/uat/feedback/{id}/moves     {to, blocking?, duplicateOf?, note?}
 * POST /api/v1/console/uat/feedback/{id}/owner     {ownerId}
 * POST /api/v1/console/uat/feedback/{id}/tracker   {url}
 * GET  /api/v1/console/uat/owners                            staff who can own an item
 * GET  /api/v1/console/uat/participants                      {items: [ParticipantView]}
 * POST /api/v1/console/uat/participants            {persona, label, contact}  (businesses: S-120's cohort)
 * POST /api/v1/console/uat/participants/{id}/deactivate
 * POST /api/v1/console/uat/participants/{id}/signoffs  {script, outcome, comments?, blockingIds?}
 * GET  /api/v1/console/uat/scripts
 * GET  /api/v1/console/uat/go-no-go                          GoNoGoReport (S-118 reads the same through uat.api)
 * GET  /api/v1/console/uat/go-no-go/export                   text/csv
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/uat")
@RequiredArgsConstructor
class UatConsoleController {

    private static final String STATE_FILTER = "open|all|" + UatBodies.STATES;

    private final UatTriage triage;
    private final UatReports reports;

    @Operation(summary = "The UAT feedback queue, newest first")
    @GetMapping("/feedback")
    @RequiresConsole(ConsoleScreen.UAT)
    ListResponse<QueueItem> queue(
            @RequestParam(defaultValue = "open") @Pattern(regexp = STATE_FILTER) String state,
            @RequestParam(required = false) @Nullable Boolean blocking,
            @RequestParam(required = false) @Nullable @Pattern(regexp = UatBodies.PERSONAS) String persona,
            @RequestParam(required = false) @Nullable @Pattern(regexp = UatBodies.APPS) String app) {
        return new ListResponse<>(triage.queue(new Filter(state, blocking, persona, app)));
    }

    @Operation(summary = "The UAT feedback queue as CSV")
    @GetMapping(value = "/feedback/export", produces = "text/csv")
    @RequiresConsole(ConsoleScreen.UAT)
    ResponseEntity<String> export(
            @RequestParam(defaultValue = "all") @Pattern(regexp = STATE_FILTER) String state,
            @RequestParam(required = false) @Nullable Boolean blocking,
            @RequestParam(required = false) @Nullable @Pattern(regexp = UatBodies.PERSONAS) String persona,
            @RequestParam(required = false) @Nullable @Pattern(regexp = UatBodies.APPS) String app,
            Locale locale) {
        return csv("uat-feedback.csv", triage.csv(new Filter(state, blocking, persona, app), locale));
    }

    @GetMapping("/feedback/{id}")
    @RequiresConsole(ConsoleScreen.UAT)
    FeedbackDetail detail(@PathVariable String id) {
        return triage.detail(id);
    }

    @Operation(summary = "The screenshot attached to a UAT feedback item")
    @GetMapping("/feedback/{id}/screenshot")
    @RequiresConsole(ConsoleScreen.UAT)
    ResponseEntity<byte[]> screenshot(@PathVariable String id) {
        return triage.screenshot(id)
                .map(s -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(s.contentType()))
                        .cacheControl(CacheControl.noStore())
                        .header("X-Content-Type-Options", "nosniff")
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .body(s.bytes().toArray()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Move a UAT feedback item on in triage (accept as blocking or not, fix, verify, merge…)")
    @PostMapping("/feedback/{id}/moves")
    @RequiresConsole(value = ConsoleScreen.UAT, actions = ConsoleAction.UAT)
    FeedbackDetail move(@PathVariable String id, @Valid @RequestBody MoveBody body, CurrentStaff staff) {
        return triage.move(
                id,
                new Move(
                        CodedEnum.fromCode(FeedbackState.class, body.to()),
                        body.blocking(),
                        body.duplicateOf(),
                        body.note()),
                actor(staff));
    }

    @PostMapping("/feedback/{id}/owner")
    @RequiresConsole(value = ConsoleScreen.UAT, actions = ConsoleAction.UAT)
    FeedbackDetail assign(@PathVariable String id, @RequestBody OwnerBody body, CurrentStaff staff) {
        return triage.assign(id, body.ownerId(), actor(staff));
    }

    @PostMapping("/feedback/{id}/tracker")
    @RequiresConsole(value = ConsoleScreen.UAT, actions = ConsoleAction.UAT)
    FeedbackDetail link(@PathVariable String id, @Valid @RequestBody TrackerBody body, CurrentStaff staff) {
        return triage.link(id, body.url(), actor(staff));
    }

    @GetMapping("/owners")
    @RequiresConsole(ConsoleScreen.UAT)
    ListResponse<Owner> owners() {
        return new ListResponse<>(triage.owners());
    }

    @GetMapping("/participants")
    @RequiresConsole(ConsoleScreen.UAT)
    ListResponse<ParticipantView> participants(Locale locale) {
        return new ListResponse<>(triage.participants(locale));
    }

    @Operation(summary = "Add a pilot participant (a customer, courier or staff member) by email or mobile number")
    @PostMapping("/participants")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.UAT, actions = ConsoleAction.UAT)
    ParticipantView add(@Valid @RequestBody ParticipantBody body, CurrentStaff staff, Locale locale) {
        return triage.addParticipant(
                new NewParticipant(body.persona(), body.label(), body.contact()), actor(staff), locale);
    }

    @PostMapping("/participants/{id}/deactivate")
    @RequiresConsole(value = ConsoleScreen.UAT, actions = ConsoleAction.UAT)
    ParticipantView deactivate(@PathVariable String id, CurrentStaff staff, Locale locale) {
        return triage.deactivate(id, actor(staff), locale);
    }

    @Operation(summary = "Record a participant's sign-off of their UAT script")
    @PostMapping("/participants/{id}/signoffs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.UAT, actions = ConsoleAction.UAT)
    ParticipantView signoff(
            @PathVariable String id, @Valid @RequestBody SignoffBody body, CurrentStaff staff, Locale locale) {
        return triage.signoff(
                id,
                new NewSignoff(
                        body.script(),
                        body.outcome(),
                        body.comments(),
                        Objects.requireNonNullElse(body.blockingIds(), List.of())),
                actor(staff),
                locale);
    }

    @GetMapping("/scripts")
    @RequiresConsole(ConsoleScreen.UAT)
    ListResponse<ScriptView> scripts(Locale locale) {
        return new ListResponse<>(triage.scripts(locale));
    }

    @Operation(summary = "UAT go/no-go: open blocking items, sign-off coverage per persona, the trend")
    @GetMapping("/go-no-go")
    @RequiresConsole(ConsoleScreen.UAT)
    ResponseEntity<GoNoGoReport> goNoGo(Locale locale) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.report(locale));
    }

    @Operation(summary = "UAT go/no-go as CSV")
    @GetMapping(value = "/go-no-go/export", produces = "text/csv")
    @RequiresConsole(ConsoleScreen.UAT)
    ResponseEntity<String> goNoGoCsv(Locale locale) {
        return csv("uat-go-no-go.csv", reports.csv(locale));
    }

    private static ResponseEntity<String> csv(String file, String body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private static Actor actor(CurrentStaff staff) {
        return new Actor(staff.userId(), staff.roleCodes());
    }
}
