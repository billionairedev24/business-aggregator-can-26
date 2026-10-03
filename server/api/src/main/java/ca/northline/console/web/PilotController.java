package ca.northline.console.web;

import ca.northline.console.application.PilotActions;
import ca.northline.console.application.PilotOnboarding;
import ca.northline.console.application.PilotOnboarding.PilotBoard;
import ca.northline.console.application.PilotOnboarding.PilotDetail;
import ca.northline.merchants.api.KitchenVisits;
import ca.northline.merchants.api.PilotCohort;
import ca.northline.shared.Bytes;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Platform console — Pilot onboarding (S-120). Screen {@code pilot} (merchant success, trust &amp; safety, admin);
 * every change needs {@code onboard} (merchant success, admin).
 *
 * <pre>
 * GET  /api/v1/console/pilot[?market=]                         PilotBoard {markets, stages, live, blocked, items}
 * GET  /api/v1/console/pilot/export[?market=]                  text/csv, one business per line
 * GET  /api/v1/console/pilot/{pilotId}                         PilotDetail {row, notes, invites, visits, visitItems}
 * POST /api/v1/console/pilot/invites                           {marketId, businessType, label, email, language, ownerId?} → 201 PilotInvited
 * POST /api/v1/console/pilot/{pilotId}/invites                 {email?, language?} → PilotInvited (the last link stops working)
 * POST /api/v1/console/pilot/enrolments                        {businessId, marketId} → 201 PilotDetail
 * PUT  /api/v1/console/pilot/{pilotId}/owner                   {ownerId?}
 * PUT  /api/v1/console/pilot/{pilotId}/blocker                 {text?, owner?} (no text = cleared)
 * POST /api/v1/console/pilot/{pilotId}/notes                   {body}
 * POST /api/v1/console/pilot/{pilotId}/kitchen-visits          {at, inspectorId?, inspectorName?}
 * POST /api/v1/console/pilot/{pilotId}/kitchen-visits/{visitId}/photos       multipart file (JPEG/PNG ≤ 10 MB)
 * GET  /api/v1/console/pilot/{pilotId}/kitchen-visits/{visitId}/photos/{photoId}
 * POST /api/v1/console/pilot/{pilotId}/kitchen-visits/{visitId}/outcome      {outcome: passed|failed, checklist, note?}
 * POST /api/v1/console/pilot/{pilotId}/kitchen-visits/{visitId}/cancel
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/pilot")
@RequiredArgsConstructor
class PilotController {

    static final String OUTCOME = "Choose passed or failed.";
    static final String BUSINESS_REQUIRED = "Choose a business.";

    private final PilotOnboarding board;
    private final PilotActions actions;

    record PilotInviteRequest(
            @NotBlank(message = PilotCohort.MARKET_REQUIRED) String marketId,
            @NotBlank(message = PilotCohort.TYPE_REQUIRED) String businessType,

            @NotBlank(message = PilotCohort.LABEL_REQUIRED) @Size(max = 80, message = PilotCohort.LABEL_REQUIRED)
            String label,

            @NotBlank(message = PilotCohort.EMAIL_REQUIRED) String email,
            @Nullable String language,
            @Nullable String ownerId) {}

    record PilotReinviteRequest(
            @Nullable String email, @Nullable String language) {}

    record PilotEnrolRequest(
            @NotBlank(message = BUSINESS_REQUIRED) String businessId,
            @NotBlank(message = PilotCohort.MARKET_REQUIRED) String marketId) {}

    record PilotOwnerRequest(@Nullable String ownerId) {}

    record PilotBlockerRequest(
            @Nullable String text, @Nullable String owner) {}

    record PilotNoteRequest(
            @NotBlank(message = PilotCohort.NOTE_REQUIRED) @Size(max = 2000, message = PilotCohort.NOTE_REQUIRED)
            String body) {}

    record PilotVisitRequest(
            @NotNull(message = KitchenVisits.WHEN_REQUIRED) Instant at,
            @Nullable String inspectorId,
            @Nullable String inspectorName) {}

    record PilotOutcomeRequest(
            @NotBlank(message = OUTCOME) @Pattern(regexp = "passed|failed", message = OUTCOME)
            String outcome,

            @Nullable Map<String, String> checklist,
            @Nullable String note) {}

    @Operation(summary = "The pilot pipeline of a market (every market without one)")
    @GetMapping
    @RequiresConsole(ConsoleScreen.PILOT)
    ResponseEntity<PilotBoard> board(@RequestParam(required = false) @Nullable String market) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(board.board(market));
    }

    @Operation(summary = "The pilot pipeline as CSV")
    @GetMapping(value = "/export", produces = "text/csv")
    @RequiresConsole(ConsoleScreen.PILOT)
    ResponseEntity<String> export(@RequestParam(required = false) @Nullable String market) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"pilot-onboarding.csv\"")
                .cacheControl(CacheControl.noStore())
                .body(board.csv(market));
    }

    @Operation(summary = "One pilot business: checklist, notes, invites, kitchen visits")
    @GetMapping("/{pilotId}")
    @RequiresConsole(ConsoleScreen.PILOT)
    PilotDetail detail(@PathVariable String pilotId) {
        return board.detail(pilotId);
    }

    @Operation(summary = "Invite a business to a market's pilot (emails a signed, expiring link)")
    @PostMapping("/invites")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.PILOT, actions = ConsoleAction.ONBOARD)
    PilotActions.PilotInvited invite(@Valid @RequestBody PilotInviteRequest body, CurrentStaff staff) {
        return actions.invite(
                new PilotCohort.NewInvite(
                        body.marketId(),
                        body.businessType(),
                        body.label(),
                        body.email(),
                        Objects.requireNonNullElse(body.language(), "en"),
                        body.ownerId()),
                actor(staff));
    }

    @Operation(summary = "Send a new invite link (the previous one stops working)")
    @PostMapping("/{pilotId}/invites")
    @RequiresConsole(value = ConsoleScreen.PILOT, actions = ConsoleAction.ONBOARD)
    PilotActions.PilotInvited reinvite(
            @PathVariable String pilotId, @RequestBody PilotReinviteRequest body, CurrentStaff staff) {
        return actions.reinvite(pilotId, body.email(), body.language(), actor(staff));
    }

    @Operation(summary = "Mark an existing business as a pilot participant of a market")
    @PostMapping("/enrolments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.PILOT, actions = ConsoleAction.ONBOARD)
    PilotDetail enrol(@Valid @RequestBody PilotEnrolRequest body, CurrentStaff staff) {
        return actions.enrol(body.businessId(), body.marketId(), actor(staff));
    }

    @Operation(summary = "Who at Northline looks after the business")
    @PutMapping("/{pilotId}/owner")
    @RequiresConsole(value = ConsoleScreen.PILOT, actions = ConsoleAction.ONBOARD)
    PilotDetail owner(@PathVariable String pilotId, @RequestBody PilotOwnerRequest body, CurrentStaff staff) {
        return actions.assign(pilotId, body.ownerId(), actor(staff));
    }

    @Operation(summary = "Write down (or clear) what holds the business up and who has to act")
    @PutMapping("/{pilotId}/blocker")
    @RequiresConsole(value = ConsoleScreen.PILOT, actions = ConsoleAction.ONBOARD)
    PilotDetail blocker(@PathVariable String pilotId, @RequestBody PilotBlockerRequest body, CurrentStaff staff) {
        return actions.block(pilotId, body.text(), body.owner(), actor(staff));
    }

    @Operation(summary = "Add a note")
    @PostMapping("/{pilotId}/notes")
    @RequiresConsole(value = ConsoleScreen.PILOT, actions = ConsoleAction.ONBOARD)
    PilotDetail note(@PathVariable String pilotId, @Valid @RequestBody PilotNoteRequest body, CurrentStaff staff) {
        return actions.note(pilotId, body.body(), actor(staff));
    }

    @Operation(summary = "Schedule a kitchen visit")
    @PostMapping("/{pilotId}/kitchen-visits")
    @RequiresConsole(value = ConsoleScreen.PILOT, actions = ConsoleAction.ONBOARD)
    PilotDetail scheduleVisit(
            @PathVariable String pilotId, @Valid @RequestBody PilotVisitRequest body, CurrentStaff staff) {
        return actions.scheduleVisit(pilotId, body.at(), body.inspectorId(), body.inspectorName(), actor(staff));
    }

    @Operation(summary = "Add a photo to a kitchen visit (stored through the storage port)")
    @PostMapping(path = "/{pilotId}/kitchen-visits/{visitId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresConsole(value = ConsoleScreen.PILOT, actions = ConsoleAction.ONBOARD)
    PilotDetail addPhoto(
            @PathVariable String pilotId,
            @PathVariable String visitId,
            @RequestParam(name = "file", required = false) @Nullable MultipartFile file,
            CurrentStaff staff) {
        if (file == null || file.isEmpty()) {
            throw RuleViolation.of("file", "required", KitchenVisits.PHOTO_TYPE);
        }
        try {
            return actions.addVisitPhoto(
                    pilotId,
                    visitId,
                    new KitchenVisits.Upload(
                            Objects.requireNonNullElse(file.getOriginalFilename(), "photo"),
                            Objects.requireNonNullElse(file.getContentType(), "application/octet-stream"),
                            Bytes.of(file.getBytes())),
                    actor(staff));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Operation(summary = "A kitchen visit's photo")
    @GetMapping("/{pilotId}/kitchen-visits/{visitId}/photos/{photoId}")
    @RequiresConsole(ConsoleScreen.PILOT)
    ResponseEntity<byte[]> photo(
            @PathVariable String pilotId, @PathVariable String visitId, @PathVariable String photoId) {
        var photo = actions.visitPhoto(pilotId, visitId, photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(photo.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(photo.bytes().toArray());
    }

    @Operation(summary = "Record a kitchen visit's outcome against the checklist")
    @PostMapping("/{pilotId}/kitchen-visits/{visitId}/outcome")
    @RequiresConsole(value = ConsoleScreen.PILOT, actions = ConsoleAction.ONBOARD)
    PilotDetail outcome(
            @PathVariable String pilotId,
            @PathVariable String visitId,
            @Valid @RequestBody PilotOutcomeRequest body,
            CurrentStaff staff) {
        var checklist = body.checklist();
        return actions.recordVisit(
                pilotId,
                visitId,
                "passed".equals(body.outcome()),
                checklist == null ? Map.of() : checklist,
                body.note(),
                actor(staff));
    }

    @Operation(summary = "Cancel a scheduled kitchen visit")
    @PostMapping("/{pilotId}/kitchen-visits/{visitId}/cancel")
    @RequiresConsole(value = ConsoleScreen.PILOT, actions = ConsoleAction.ONBOARD)
    PilotDetail cancelVisit(@PathVariable String pilotId, @PathVariable String visitId, CurrentStaff staff) {
        return actions.cancelVisit(pilotId, visitId, actor(staff));
    }

    private static PilotCohort.Actor actor(CurrentStaff staff) {
        return new PilotCohort.Actor(staff.userId(), staff.roleCodes());
    }
}
