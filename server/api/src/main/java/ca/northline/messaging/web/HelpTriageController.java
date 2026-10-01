package ca.northline.messaging.web;

import ca.northline.messaging.application.TriageHelpRequest;
import ca.northline.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-132: {@code POST /api/v1/me/help/triage} — the signed-in customer's "something's wrong" text → a suggested case
 * category, route and a summary for staff. Opens nothing; the S-60 flow shows the suggestion and opens the case the
 * customer confirms.
 */
@RestController
@RequiredArgsConstructor
class HelpTriageController {

    private final TriageHelpRequest triage;

    record Body(
            @Nullable String text,

            @Nullable @Pattern(regexp = "order|booking", message = "Choose order or booking.")
            String refType) {}

    @PostMapping("/api/v1/me/help/triage")
    TriageHelpRequest.Triage triage(@Valid @RequestBody Body body, CurrentUser user) {
        return triage.triage(user.userId(), body.text() == null ? "" : body.text(), body.refType());
    }
}
