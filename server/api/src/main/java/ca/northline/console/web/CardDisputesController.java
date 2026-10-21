package ca.northline.console.web;

import ca.northline.console.application.CardDisputeDesk;
import ca.northline.console.application.CardDisputeDesk.Detail;
import ca.northline.console.application.CardDisputeDesk.Item;
import ca.northline.payments.api.CardDisputes;
import ca.northline.shared.ListResponse;
import ca.northline.shared.NotFound;
import ca.northline.shared.PlaceFilter;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — card disputes (S-156): chargebacks from Stripe's webhooks, their deadline and fee, and the
 * evidence an agent submits to Stripe. Screen disputes; saving and submitting evidence need {@code decide} (admin,
 * trust &amp; safety), like the other dispute decisions.
 *
 * <pre>
 * GET  /api/v1/console/card-disputes[?province=&amp;market=]   {items: [Item]}
 * GET  /api/v1/console/card-disputes/{id}                     Detail
 * PUT  /api/v1/console/card-disputes/{id}/evidence {fields: {name: text}, files: {name: evidenceId}}   Detail
 * POST /api/v1/console/card-disputes/{id}/evidence/submit                                             Detail
 *      409 evidence_submitted · dispute_closed · evidence_overdue · evidence_empty · stripe_rejected · not_card_dispute
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/card-disputes")
@RequiredArgsConstructor
class CardDisputesController {

    private final PlaceFilter places;
    private final CardDisputeDesk desk;

    record EvidenceRequest(
            @Nullable Map<String, String> fields, @Nullable Map<String, String> files) {}

    @GetMapping
    @RequiresConsole(ConsoleScreen.DISPUTES)
    ListResponse<Item> list(
            @RequestParam(required = false) @Nullable String province,
            @RequestParam(required = false) @Nullable String market) {
        return new ListResponse<>(desk.list(places.resolve(province, market).scope()));
    }

    @GetMapping("/{id}")
    @RequiresConsole(ConsoleScreen.DISPUTES)
    Detail detail(@PathVariable String id) {
        return desk.detail(id).orElseThrow(() -> new NotFound("dispute", id));
    }

    @PutMapping("/{id}/evidence")
    @RequiresConsole(value = ConsoleScreen.DISPUTES, actions = ConsoleAction.DECIDE)
    Detail save(@PathVariable String id, @Valid @RequestBody EvidenceRequest body, CurrentStaff staff) {
        var draft = new CardDisputes.Draft(present(body.fields()), present(body.files()));
        return desk.save(id, draft, staff.userId(), staff.roleCodes());
    }

    /** JSON nulls dropped (a cleared field is the same as an absent one). */
    private static Map<String, String> present(@Nullable Map<String, String> map) {
        var out = new java.util.LinkedHashMap<String, String>();
        if (map != null) {
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(k, v);
                }
            });
        }
        return out;
    }

    @PostMapping("/{id}/evidence/submit")
    @RequiresConsole(value = ConsoleScreen.DISPUTES, actions = ConsoleAction.DECIDE)
    Detail submit(@PathVariable String id, CurrentStaff staff) {
        return desk.submit(id, staff.userId(), staff.roleCodes());
    }
}
