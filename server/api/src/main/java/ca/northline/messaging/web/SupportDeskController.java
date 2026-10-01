package ca.northline.messaging.web;

import ca.northline.messaging.application.SupportDesk;
import ca.northline.messaging.application.SupportDesk.Filter;
import ca.northline.messaging.application.SupportDesk.Macro;
import ca.northline.messaging.application.SupportDesk.MacroInput;
import ca.northline.messaging.application.SupportDesk.PendingRefund;
import ca.northline.messaging.application.SupportDesk.Queue;
import ca.northline.messaging.application.SupportDesk.TicketDetail;
import ca.northline.shared.PlaceFilter;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — the support desk (S-83, design 03 {@code support}). Support, support leads, trust &amp; safety,
 * dispatch and admins open it; acting on a case needs {@code support}; editing macros needs {@code macros} (support
 * leads, admins); refund requests are decided by someone with {@code refund} on the finance screen (finance, admins) —
 * never the agent who asked.
 *
 * <pre>
 * GET    /api/v1/console/support/tickets[?filter=all|urgent|unassigned|mine|sla_risk|providers|…][&amp;province=][&amp;market=]
 *                                                                            {kpis, counts, items: [Ticket]}
 * GET    /api/v1/console/support/tickets/{id}                                TicketDetail
 * POST   /api/v1/console/support/tickets/{id}/reply      {body, resolve?, macroKey?}   TicketDetail
 * POST   /api/v1/console/support/tickets/{id}/take                                     TicketDetail
 * POST   /api/v1/console/support/tickets/{id}/escalate   {note?}                       TicketDetail
 * POST   /api/v1/console/support/tickets/{id}/refund-requests {amountCents, note?}     TicketDetail
 * GET    /api/v1/console/support/refund-requests[?province=][&amp;market=]   {items: [PendingRefund]}   (finance)
 * POST   /api/v1/console/support/refund-requests/{id}/decision {decision: approve|decline, note?}  TicketDetail
 * GET    /api/v1/console/support/macros                                       {items: [Macro]}
 * POST   /api/v1/console/support/macros                  {key, title: {en, fr}, body: {en, fr}}   Macro (201)
 * PUT    /api/v1/console/support/macros/{id}             {key, title, body}             Macro
 * DELETE /api/v1/console/support/macros/{id}                                           204
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/support")
@RequiredArgsConstructor
class SupportDeskController {

    static final String FILTER = "Pick a filter from the list.";
    static final String NOTE_TOO_LONG = "At most 1,000 characters.";

    private final PlaceFilter places;
    private final SupportDesk desk;

    record ReplyRequest(
            @NotBlank(message = SupportDesk.BODY_REQUIRED)
            @Size(max = SupportDesk.BODY_MAX, message = SupportDesk.BODY_TOO_LONG)
            String body,

            @Nullable Boolean resolve,

            @Nullable @Size(max = 60, message = SupportDesk.MACRO_KEY)
            String macroKey) {}

    record NoteRequest(
            @Nullable @Size(max = 1000, message = NOTE_TOO_LONG)
            String note) {}

    record RefundRequestBody(
            @NotNull(message = SupportDesk.AMOUNT_RANGE) @Positive(message = SupportDesk.AMOUNT_RANGE)
            Long amountCents,

            @Nullable @Size(max = 1000, message = NOTE_TOO_LONG)
            String note) {}

    record DecisionRequest(
            @NotBlank(message = SupportDesk.DECISION_REQUIRED)
            @Pattern(regexp = "approve|decline", message = SupportDesk.DECISION_REQUIRED)
            String decision,

            @Nullable @Size(max = 1000, message = NOTE_TOO_LONG)
            String note) {}

    record MacroRequest(
            @NotBlank(message = SupportDesk.MACRO_KEY) String key,
            @Nullable Map<String, String> title,
            @Nullable Map<String, String> body) {}

    record Items<T>(List<T> items) {}

    @GetMapping("/tickets")
    @RequiresConsole(ConsoleScreen.SUPPORT)
    Queue tickets(
            @RequestParam(required = false) @Nullable String filter,
            @RequestParam(required = false) @Nullable String province,
            @RequestParam(required = false) @Nullable String market,
            CurrentStaff staff) {
        return desk.queue(places.resolve(province, market).scope(), filter(filter), staff.userId());
    }

    @GetMapping("/tickets/{id}")
    @RequiresConsole(ConsoleScreen.SUPPORT)
    TicketDetail ticket(@PathVariable String id) {
        return desk.ticket(id);
    }

    @PostMapping("/tickets/{id}/reply")
    @RequiresConsole(value = ConsoleScreen.SUPPORT, actions = ConsoleAction.SUPPORT)
    TicketDetail reply(@PathVariable String id, @Valid @RequestBody ReplyRequest body, CurrentStaff staff) {
        return desk.reply(new SupportDesk.Reply(
                id,
                body.body(),
                Boolean.TRUE.equals(body.resolve()),
                body.macroKey() == null || body.macroKey().isBlank() ? null : body.macroKey(),
                staff.userId(),
                staff.roleCodes()));
    }

    @PostMapping("/tickets/{id}/take")
    @RequiresConsole(value = ConsoleScreen.SUPPORT, actions = ConsoleAction.SUPPORT)
    TicketDetail take(@PathVariable String id, CurrentStaff staff) {
        return desk.take(id, staff.userId(), staff.roleCodes());
    }

    @PostMapping("/tickets/{id}/escalate")
    @RequiresConsole(value = ConsoleScreen.SUPPORT, actions = ConsoleAction.SUPPORT)
    TicketDetail escalate(
            @PathVariable String id,
            @Valid @RequestBody(required = false) @Nullable NoteRequest body,
            CurrentStaff staff) {
        return desk.escalate(id, body == null ? null : body.note(), staff.userId(), staff.roleCodes());
    }

    @PostMapping("/tickets/{id}/refund-requests")
    @RequiresConsole(value = ConsoleScreen.SUPPORT, actions = ConsoleAction.SUPPORT)
    TicketDetail requestRefund(
            @PathVariable String id, @Valid @RequestBody RefundRequestBody body, CurrentStaff staff) {
        var amount = body.amountCents();
        if (amount == null) {
            throw RuleViolation.of("amountCents", "required", SupportDesk.AMOUNT_RANGE);
        }
        return desk.requestRefund(id, amount, body.note(), staff.userId(), staff.roleCodes());
    }

    @GetMapping("/refund-requests")
    @RequiresConsole(ConsoleScreen.FINANCE)
    Items<PendingRefund> pendingRefunds(
            @RequestParam(required = false) @Nullable String province,
            @RequestParam(required = false) @Nullable String market) {
        return new Items<>(desk.pendingRefunds(places.resolve(province, market).scope()));
    }

    @PostMapping("/refund-requests/{id}/decision")
    @RequiresConsole(value = ConsoleScreen.FINANCE, actions = ConsoleAction.REFUND)
    TicketDetail decideRefund(@PathVariable String id, @Valid @RequestBody DecisionRequest body, CurrentStaff staff) {
        return desk.decideRefund(id, "approve".equals(body.decision()), body.note(), staff.userId(), staff.roleCodes());
    }

    @GetMapping("/macros")
    @RequiresConsole(ConsoleScreen.SUPPORT)
    Items<Macro> macros() {
        return new Items<>(desk.macros());
    }

    @PostMapping("/macros")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.SUPPORT, actions = ConsoleAction.MACROS)
    Macro createMacro(@Valid @RequestBody MacroRequest body, CurrentStaff staff) {
        return desk.saveMacro(null, input(body), staff.userId(), staff.roleCodes());
    }

    @PutMapping("/macros/{id}")
    @RequiresConsole(value = ConsoleScreen.SUPPORT, actions = ConsoleAction.MACROS)
    Macro updateMacro(@PathVariable String id, @Valid @RequestBody MacroRequest body, CurrentStaff staff) {
        return desk.saveMacro(id, input(body), staff.userId(), staff.roleCodes());
    }

    @DeleteMapping("/macros/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresConsole(value = ConsoleScreen.SUPPORT, actions = ConsoleAction.MACROS)
    void deleteMacro(@PathVariable String id, CurrentStaff staff) {
        desk.deleteMacro(id, staff.userId(), staff.roleCodes());
    }

    private static MacroInput input(MacroRequest body) {
        return new MacroInput(
                body.key(),
                body.title() == null ? Map.of() : withoutNulls(body.title()),
                body.body() == null ? Map.of() : withoutNulls(body.body()));
    }

    private static Map<String, String> withoutNulls(Map<String, String> text) {
        return Map.copyOf(text.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    private static Filter filter(@Nullable String code) {
        if (code == null || code.isBlank()) {
            return Filter.ALL;
        }
        return Arrays.stream(Filter.values())
                .filter(f -> f.code().equals(code))
                .findFirst()
                .orElseThrow(() -> RuleViolation.of("filter", "unknown", FILTER));
    }
}
