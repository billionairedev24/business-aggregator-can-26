package ca.northline.privacy.web;

import ca.northline.privacy.application.PrivacyRequests.Actor;
import ca.northline.privacy.application.PrivacyRequests.Desk;
import ca.northline.privacy.application.PrivacyRequests.DeskItem;
import ca.northline.privacy.application.PrivacyRequests.RequestView;
import ca.northline.privacy.domain.Decision;
import ca.northline.privacy.domain.ExtensionReason;
import ca.northline.privacy.domain.RequestType;
import ca.northline.privacy.web.PrivacyRequestBodies.CorrectBody;
import ca.northline.privacy.web.PrivacyRequestBodies.ExtendBody;
import ca.northline.privacy.web.PrivacyRequestBodies.RecordBody;
import ca.northline.privacy.web.PrivacyRequestBodies.RejectBody;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — privacy requests (S-105): the queue with each request's law and SLA clock, the detail (what was
 * asked, the erasure's steps, what is kept and why), and staff actions. Screen {@code privacy} (admin, privacy
 * officer, support lead); every change needs the {@code privacy} action.
 *
 * <pre>
 * GET  /api/v1/console/privacy-requests[?state=open|closed][&amp;type=access|correction|erasure]  {items: [DeskItem]}
 * POST /api/v1/console/privacy-requests  {contact, type, corrections?, note?}  → 201 (made by email, phone or mail)
 * GET  /api/v1/console/privacy-requests/{id}                         RequestView
 * POST /api/v1/console/privacy-requests/{id}/verify                  staff checked who the person is
 * POST /api/v1/console/privacy-requests/{id}/extend  {reason}        once, as far as the law allows
 * POST /api/v1/console/privacy-requests/{id}/reject  {decision, note?}
 * POST /api/v1/console/privacy-requests/{id}/start                   a verified erasure, now
 * POST /api/v1/console/privacy-requests/{id}/corrections  {corrections: [{field, value}]}   apply and complete
 * POST /api/v1/console/privacy-requests/{id}/retry                   failed or held erasure steps, now
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/privacy-requests")
@RequiredArgsConstructor
class PrivacyDeskController {

    private final Desk desk;

    @Operation(summary = "The privacy request queue, the soonest deadline first")
    @GetMapping
    @RequiresConsole(ConsoleScreen.PRIVACY)
    ListResponse<DeskItem> queue(
            @RequestParam(defaultValue = "open") @Pattern(regexp = "open|closed") String state,
            @RequestParam(required = false) @Nullable @Pattern(regexp = PrivacyRequestBodies.TYPES) String type,
            Locale locale) {
        return new ListResponse<>(desk.queue(
                "open".equals(state), type == null ? null : CodedEnum.fromCode(RequestType.class, type), locale));
    }

    @Operation(summary = "Record a privacy request made by email, phone or mail")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.PRIVACY, actions = ConsoleAction.PRIVACY)
    RequestView record(@Valid @RequestBody RecordBody body, CurrentStaff staff, Locale locale) {
        return desk.record(
                new Desk.Recorded(
                        body.contact(),
                        CodedEnum.fromCode(RequestType.class, body.type()),
                        PrivacyRequestBodies.corrections(body.corrections()),
                        body.note(),
                        actor(staff)),
                locale);
    }

    @GetMapping("/{id}")
    @RequiresConsole(ConsoleScreen.PRIVACY)
    RequestView detail(@PathVariable String id, Locale locale) {
        return desk.detail(id, locale);
    }

    @PostMapping("/{id}/verify")
    @RequiresConsole(value = ConsoleScreen.PRIVACY, actions = ConsoleAction.PRIVACY)
    RequestView verify(@PathVariable String id, CurrentStaff staff, Locale locale) {
        return desk.verify(id, actor(staff), locale);
    }

    @PostMapping("/{id}/extend")
    @RequiresConsole(value = ConsoleScreen.PRIVACY, actions = ConsoleAction.PRIVACY)
    RequestView extend(
            @PathVariable String id, @Valid @RequestBody ExtendBody body, CurrentStaff staff, Locale locale) {
        return desk.extend(id, CodedEnum.fromCode(ExtensionReason.class, body.reason()), actor(staff), locale);
    }

    @PostMapping("/{id}/reject")
    @RequiresConsole(value = ConsoleScreen.PRIVACY, actions = ConsoleAction.PRIVACY)
    RequestView reject(
            @PathVariable String id, @Valid @RequestBody RejectBody body, CurrentStaff staff, Locale locale) {
        return desk.reject(id, CodedEnum.fromCode(Decision.class, body.decision()), body.note(), actor(staff), locale);
    }

    @PostMapping("/{id}/start")
    @RequiresConsole(value = ConsoleScreen.PRIVACY, actions = ConsoleAction.PRIVACY)
    RequestView start(@PathVariable String id, CurrentStaff staff, Locale locale) {
        return desk.start(id, actor(staff), locale);
    }

    @PostMapping("/{id}/corrections")
    @RequiresConsole(value = ConsoleScreen.PRIVACY, actions = ConsoleAction.PRIVACY)
    RequestView correct(
            @PathVariable String id, @Valid @RequestBody CorrectBody body, CurrentStaff staff, Locale locale) {
        return desk.correct(
                id, Objects.requireNonNull(PrivacyRequestBodies.corrections(body.corrections())), actor(staff), locale);
    }

    @PostMapping("/{id}/retry")
    @RequiresConsole(value = ConsoleScreen.PRIVACY, actions = ConsoleAction.PRIVACY)
    RequestView retry(@PathVariable String id, CurrentStaff staff, Locale locale) {
        return desk.retry(id, actor(staff), locale);
    }

    private static Actor actor(CurrentStaff staff) {
        return new Actor(staff.userId(), staff.roleCodes());
    }
}
