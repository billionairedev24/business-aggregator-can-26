package ca.northline.restricted.web;

import ca.northline.restricted.api.AgeVerifications.Status;
import ca.northline.restricted.application.AgeVerificationUseCases.ReadAgeCheck;
import ca.northline.restricted.application.AgeVerificationUseCases.StartAgeCheck;
import ca.northline.restricted.application.AgeVerificationUseCases.Started;
import ca.northline.restricted.domain.AgeMessages;
import ca.northline.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's age check (consumer site and app; checkout asks for it when the cart has age-restricted items).
 *
 * <pre>
 * GET  /api/v1/me/age-verification            {state: none|pending|verified|failed, overAge, verifiedOn, ageFloor, method, lastError}
 * POST /api/v1/me/age-verification {returnTo: web|app}   {url, status} — open url (the provider's hosted flow) at once;
 *                                             409 age_already_verified | age_check_attempts | age_check_unavailable
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me/age-verification")
@RequiredArgsConstructor
class AgeVerificationController {

    private final ReadAgeCheck read;
    private final StartAgeCheck start;

    record StartRequest(
            @NotBlank(message = AgeMessages.RETURN_URL) @Pattern(regexp = "web|app", message = AgeMessages.RETURN_URL)
            String returnTo) {}

    @GetMapping
    Status status(CurrentUser user) {
        return read.read(user.userId());
    }

    @PostMapping
    Started start(CurrentUser user, @Valid @RequestBody StartRequest body) {
        return start.start(user.userId(), body.returnTo());
    }
}
