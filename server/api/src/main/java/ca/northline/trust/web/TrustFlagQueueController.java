package ca.northline.trust.web;

import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentUser;
import ca.northline.shared.security.MerchantAccessDenied;
import ca.northline.trust.application.TrustFlagQueue;
import ca.northline.trust.application.TrustFlagQueue.FlagView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 * {@code source: ai}), and the staff decision. {@code /api/v1/console/**} needs role {@code STAFF} and a second factor.
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

    record DecisionRequest(
            @NotBlank(message = TrustFlagQueue.DECISION_REQUIRED)
            @Pattern(regexp = "dismissed|actioned", message = TrustFlagQueue.DECISION_REQUIRED)
            String decision,

            @Nullable @Size(max = 500, message = "At most 500 characters.")
            String note) {}

    @GetMapping
    ListResponse<FlagView> list(
            @RequestParam(defaultValue = "open") @Pattern(regexp = "open|dismissed|actioned|all") String state,
            @RequestParam(required = false) @Nullable @Pattern(regexp = "ai|rules") String source,
            @RequestParam(defaultValue = "50") int limit,
            CurrentUser user) {
        requireMfa(user);
        return new ListResponse<>(queue.list("all".equals(state) ? null : state, source, limit));
    }

    @PostMapping("/{id}/decision")
    FlagView decide(@PathVariable String id, @Valid @RequestBody DecisionRequest body, CurrentUser user) {
        requireMfa(user);
        return queue.decide(id, body.decision(), user.userId(), body.note());
    }

    private static void requireMfa(CurrentUser user) {
        if (!user.mfa()) {
            throw new MerchantAccessDenied(
                    MerchantAccessDenied.Reason.MFA_REQUIRED, "Sign in with your second factor to do this.");
        }
    }
}
