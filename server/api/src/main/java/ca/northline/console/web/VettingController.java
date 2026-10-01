package ca.northline.console.web;

import ca.northline.console.application.ListingVettingQueue;
import ca.northline.console.application.ListingVettingQueue.Item;
import ca.northline.console.application.ListingVettingQueue.Queue;
import ca.northline.shared.PlaceFilter;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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
 * Platform console — listing vetting (S-92, design 03 {@code vetting}; admin and trust &amp; safety open it, deciding
 * needs {@code vet}).
 *
 * <pre>
 * GET  /api/v1/console/vetting[?province=AB][&amp;market=calgary]          {autoApproved, flagged, items: [Item]}
 * POST /api/v1/console/vetting/listings/{id}/decision {decision: approve|reject, reasons?, note?}   Item
 * POST /api/v1/console/vetting/dishes/{id}/decision   {decision: approve|reject, reasons?, note?}   Item
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/vetting")
@RequiredArgsConstructor
class VettingController {

    private final PlaceFilter places;
    private final ListingVettingQueue vetting;

    record DecisionRequest(
            @NotBlank(message = ListingVettingQueue.DECISION_REQUIRED)
            @Pattern(regexp = "approve|reject", message = ListingVettingQueue.DECISION_REQUIRED)
            String decision,

            @Nullable List<String> reasons,

            @Nullable @Size(max = 500, message = "At most 500 characters.")
            String note) {}

    @GetMapping
    @RequiresConsole(ConsoleScreen.VETTING)
    Queue queue(
            @RequestParam(required = false) @Nullable String province,
            @RequestParam(required = false) @Nullable String market) {
        return vetting.queue(places.resolve(province, market).scope());
    }

    @PostMapping("/listings/{id}/decision")
    @RequiresConsole(value = ConsoleScreen.VETTING, actions = ConsoleAction.VET)
    Item decideListing(@PathVariable String id, @Valid @RequestBody DecisionRequest body, CurrentStaff staff) {
        return decide("listing", id, body, staff);
    }

    @PostMapping("/dishes/{id}/decision")
    @RequiresConsole(value = ConsoleScreen.VETTING, actions = ConsoleAction.VET)
    Item decideDish(@PathVariable String id, @Valid @RequestBody DecisionRequest body, CurrentStaff staff) {
        return decide("dish", id, body, staff);
    }

    private Item decide(String kind, String id, DecisionRequest body, CurrentStaff staff) {
        return vetting.decide(new ListingVettingQueue.Decision(
                kind,
                id,
                "approve".equals(body.decision()),
                body.reasons() == null ? List.of() : body.reasons(),
                body.note(),
                staff.userId(),
                staff.roleCodes()));
    }
}
