package ca.northline.trust.web;

import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import ca.northline.trust.application.ReviewModeration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — hiding a review outside the flag queue, and showing a hidden one again (mobile gaps part 2). The
 * queue's {@code hide_review} action is the usual way; both are audit-logged ({@code trust.review_hidden} /
 * {@code trust.review_shown}). Trust screen, {@code decide}.
 *
 * <pre>
 * POST /api/v1/console/trust/reviews/{id}/hide {reason, note?}   {changed}
 * POST /api/v1/console/trust/reviews/{id}/show {note?}           {changed}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/trust/reviews/{id}")
@RequiredArgsConstructor
class ReviewModerationController {

    private final ReviewModeration moderation;

    record HideRequest(
            @NotBlank(message = ReviewModeration.REASON_REQUIRED)
            @Size(max = 60, message = ReviewModeration.REASON_REQUIRED)
            String reason,

            @Nullable @Size(max = 500, message = "At most 500 characters.")
            String note) {}

    record ShowRequest(
            @Nullable @Size(max = 500, message = "At most 500 characters.")
            String note) {}

    @PostMapping("/hide")
    @RequiresConsole(value = ConsoleScreen.TRUST, actions = ConsoleAction.DECIDE)
    Map<String, Boolean> hide(@PathVariable String id, @Valid @RequestBody HideRequest body, CurrentStaff staff) {
        return Map.of("changed", moderation.hide(id, body.reason(), staff.userId(), staff.roleCodes(), body.note()));
    }

    @PostMapping("/show")
    @RequiresConsole(value = ConsoleScreen.TRUST, actions = ConsoleAction.DECIDE)
    Map<String, Boolean> show(@PathVariable String id, @Valid @RequestBody ShowRequest body, CurrentStaff staff) {
        return Map.of("changed", moderation.show(id, staff.userId(), staff.roleCodes(), body.note()));
    }
}
