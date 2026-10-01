package ca.northline.merchants.web;

import ca.northline.merchants.application.RegistryReviews;
import ca.northline.merchants.application.RegistryReviews.DecideReview;
import ca.northline.merchants.application.RegistryReviews.ListReviews;
import ca.northline.merchants.application.RegistryReviews.ReviewView;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentUser;
import ca.northline.shared.security.MerchantAccessDenied;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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
 * Platform console — registry verification queue (S-23): lookups that didn't match, with their evidence, and the
 * agent's decision. {@code /api/v1/console/**} needs role {@code STAFF}; a second factor is required too.
 * S-90: the verification queue (admin, trust &amp; safety); deciding needs {@code verify}.
 *
 * <pre>
 * GET  /api/v1/console/registry-reviews[?limit=50]                                      {items: [ReviewView]}
 * POST /api/v1/console/registry-reviews/{id}/decision {decision: approve|reject, reference?, expiresOn?, note?}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/registry-reviews")
@RequiredArgsConstructor
class RegistryReviewController {

    private final ListReviews reviews;
    private final DecideReview decide;

    record DecisionRequest(
            @NotBlank(message = RegistryReviews.DECISION_REQUIRED)
            @Pattern(regexp = "approve|reject", message = RegistryReviews.DECISION_REQUIRED)
            String decision,

            @Nullable @Size(max = 120, message = "At most 120 characters.")
            String reference,

            @Nullable LocalDate expiresOn,

            @Nullable @Size(max = 500, message = "At most 500 characters.")
            String note) {}

    @GetMapping
    @RequiresConsole(ConsoleScreen.VERIFY)
    ListResponse<ReviewView> open(@RequestParam(defaultValue = "50") int limit, CurrentUser user) {
        requireMfa(user);
        return new ListResponse<>(reviews.open(Math.clamp(limit, 1, 200)));
    }

    @PostMapping("/{id}/decision")
    @RequiresConsole(value = ConsoleScreen.VERIFY, actions = ConsoleAction.VERIFY)
    ReviewView decide(@PathVariable String id, @Valid @RequestBody DecisionRequest body, CurrentUser user) {
        requireMfa(user);
        return decide.decide(new DecideReview.Command(
                id, "approve".equals(body.decision()), user.userId(), body.note(), body.reference(), body.expiresOn()));
    }

    private static void requireMfa(CurrentUser user) {
        if (!user.mfa()) {
            throw new MerchantAccessDenied(
                    MerchantAccessDenied.Reason.MFA_REQUIRED, "Sign in with your second factor to do this.");
        }
    }
}
