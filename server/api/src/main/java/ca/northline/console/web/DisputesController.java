package ca.northline.console.web;

import ca.northline.console.application.DisputeDesk;
import ca.northline.console.application.DisputeDesk.Detail;
import ca.northline.console.application.DisputeDesk.Item;
import ca.northline.console.application.DisputeDesk.Queue;
import ca.northline.payments.api.AgentCases;
import ca.northline.shared.NotFound;
import ca.northline.shared.PlaceFilter;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
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
 * Platform console — disputes &amp; refunds (S-80, design 03 {@code disputes}). Admin, trust &amp; safety, finance and
 * support open it; deciding needs {@code decide} (admin, trust &amp; safety); co-signing a decision above $500 needs
 * {@code refund} (admin, finance) and another person than the agent.
 *
 * <pre>
 * GET  /api/v1/console/disputes[?province=&amp;market=]                         {summary, items: [Item]}
 * GET  /api/v1/console/disputes/{kind}/{id}                                   Detail   (kind = dispute | refund)
 * GET  /api/v1/console/disputes/dispute/{id}/evidence/{evidenceId}            the file
 * POST /api/v1/console/disputes/{kind}/{id}/decision {outcome, refundCents?, note?}            Item
 * POST /api/v1/console/disputes/decisions/{decisionId}/cosign {decision: approve|decline, note?} Item
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/disputes")
@RequiredArgsConstructor
class DisputesController {

    private final PlaceFilter places;
    private final DisputeDesk desk;

    record DecisionRequest(
            @NotBlank(message = AgentCases.OUTCOME_REQUIRED)
            @Pattern(regexp = "full_refund|partial|release|goodwill_credit", message = AgentCases.OUTCOME_REQUIRED)
            String outcome,

            @Nullable @Min(value = 0, message = AgentCases.PARTIAL_RANGE)
            Long refundCents,

            @Nullable @Size(max = 1000, message = "At most 1,000 characters.")
            String note) {}

    record CosignRequest(
            @NotNull(message = COSIGN_REQUIRED) @Pattern(regexp = "approve|decline", message = COSIGN_REQUIRED)
            String decision,

            @Nullable @Size(max = 1000, message = "At most 1,000 characters.")
            String note) {}

    static final String COSIGN_REQUIRED = "Choose approve or decline.";

    @GetMapping
    @RequiresConsole(ConsoleScreen.DISPUTES)
    Queue queue(
            @RequestParam(required = false) @Nullable String province,
            @RequestParam(required = false) @Nullable String market) {
        return desk.queue(places.resolve(province, market).scope());
    }

    @GetMapping("/{kind}/{id}")
    @RequiresConsole(ConsoleScreen.DISPUTES)
    Detail detail(@PathVariable String kind, @PathVariable String id) {
        return desk.detail(kind(kind, id), id).orElseThrow(() -> new NotFound(kind, id));
    }

    @GetMapping("/dispute/{id}/evidence/{evidenceId}")
    @RequiresConsole(ConsoleScreen.DISPUTES)
    ResponseEntity<byte[]> evidence(@PathVariable String id, @PathVariable String evidenceId) {
        var file = desk.evidence(id, evidenceId).orElseThrow(() -> new NotFound("evidence", evidenceId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(file.name())
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes().toArray());
    }

    @PostMapping("/{kind}/{id}/decision")
    @RequiresConsole(value = ConsoleScreen.DISPUTES, actions = ConsoleAction.DECIDE)
    Item decide(
            @PathVariable String kind,
            @PathVariable String id,
            @Valid @RequestBody DecisionRequest body,
            CurrentStaff staff) {
        return desk.decide(new AgentCases.Decide(
                kind(kind, id),
                id,
                AgentCases.Outcome.valueOf(body.outcome().toUpperCase(Locale.ROOT)),
                body.refundCents() == null ? 0 : body.refundCents(),
                body.note(),
                staff.userId(),
                staff.roleCodes()));
    }

    /** {@code dispute | refund}; anything else is a case that doesn't exist. */
    private static String kind(String kind, String id) {
        if (!"dispute".equals(kind) && !"refund".equals(kind)) {
            throw new NotFound("case", id);
        }
        return kind;
    }

    @PostMapping("/decisions/{decisionId}/cosign")
    @RequiresConsole(value = ConsoleScreen.DISPUTES, actions = ConsoleAction.REFUND)
    Item cosign(@PathVariable String decisionId, @Valid @RequestBody CosignRequest body, CurrentStaff staff) {
        return desk.cosign(new AgentCases.Cosign(
                decisionId, "approve".equals(body.decision()), body.note(), staff.userId(), staff.roleCodes()));
    }
}
