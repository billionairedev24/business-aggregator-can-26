package ca.northline.privacy.web;

import ca.northline.privacy.application.PrivacyRequests.Link;
import ca.northline.privacy.application.PrivacyRequests.RequestView;
import ca.northline.privacy.application.PrivacyRequests.SelfService;
import ca.northline.privacy.domain.RequestType;
import ca.northline.privacy.web.PrivacyRequestBodies.OpenBody;
import ca.northline.privacy.web.PrivacyRequestBodies.VerifyBody;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's privacy requests (S-105): consumers and team members alike, from the consumer site, the app or the
 * Studio. Every request needs the person to prove it is them — a fresh step-up proof in {@code X-Step-Up}, or the code
 * texted to the account's verified mobile.
 *
 * <pre>
 * GET  /api/v1/me/privacy-requests                         {items: [RequestView]} newest first
 * GET  /api/v1/me/privacy-requests/correctable-fields      {items: ["phone", …]}
 * POST /api/v1/me/privacy-requests  {type, corrections?, note?}  [X-Step-Up]  → 201 RequestView
 *        verified at once with a valid proof; otherwise awaiting_verification and a code texted (codeSentTo)
 * GET  /api/v1/me/privacy-requests/{id}                    RequestView
 * POST /api/v1/me/privacy-requests/{id}/verification-code  texts a new code
 * POST /api/v1/me/privacy-requests/{id}/verify  {code}  | (no code) [X-Step-Up]
 * POST /api/v1/me/privacy-requests/{id}/withdraw           before an erasure starts
 * POST /api/v1/me/privacy-requests/{id}/download-link      → {url, summaryUrl, expiresAt} (a ready access export)
 * </pre>
 *
 * 403 {@code step_up_required} for a stale or missing proof; 409 {@code request_open}, {@code not_awaiting}, {@code
 * code_locked}, {@code code_too_soon}, {@code too_many_codes} (S-104), {@code no_mobile}, {@code not_withdrawable}, {@code closed}, {@code
 * export_not_ready}, {@code export_gone}, {@code account_erased}. Someone else's request is a 404.
 */
@RestController
@RequestMapping("/api/v1/me/privacy-requests")
@RequiredArgsConstructor
class MyPrivacyRequestsController {

    static final String STEP_UP = "X-Step-Up";

    private final SelfService requests;

    @Operation(summary = "The caller's privacy requests (access, correction, erasure)")
    @GetMapping
    ListResponse<RequestView> mine(CurrentUser user, Locale locale) {
        return new ListResponse<>(requests.mine(user.userId(), locale));
    }

    @Operation(summary = "Fields the caller can ask to have corrected")
    @GetMapping("/correctable-fields")
    ListResponse<String> correctable() {
        return new ListResponse<>(requests.correctable().stream().sorted().toList());
    }

    @Operation(summary = "Ask for a copy of one's data, a correction, or deletion of the account")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RequestView open(
            CurrentUser user,
            @Valid @RequestBody OpenBody body,
            @RequestHeader(name = STEP_UP, required = false) @Nullable String proof,
            Locale locale) {
        return requests.open(
                new SelfService.Open(
                        user.userId(),
                        CodedEnum.fromCode(RequestType.class, body.type()),
                        PrivacyRequestBodies.corrections(body.corrections()),
                        body.note(),
                        proof),
                locale);
    }

    @GetMapping("/{id}")
    RequestView one(CurrentUser user, @PathVariable String id, Locale locale) {
        return requests.one(user.userId(), id, locale);
    }

    @Operation(summary = "Text a new verification code to the account's mobile")
    @PostMapping("/{id}/verification-code")
    RequestView code(CurrentUser user, @PathVariable String id, Locale locale) {
        return requests.sendCode(user.userId(), id, locale);
    }

    @Operation(summary = "Verify a request with the texted code, or with a step-up proof")
    @PostMapping("/{id}/verify")
    RequestView verify(
            CurrentUser user,
            @PathVariable String id,
            @Valid @RequestBody(required = false) @Nullable VerifyBody body,
            @RequestHeader(name = STEP_UP, required = false) @Nullable String proof,
            Locale locale) {
        return requests.verify(user.userId(), id, body == null ? null : body.code(), proof, locale);
    }

    @PostMapping("/{id}/withdraw")
    RequestView withdraw(CurrentUser user, @PathVariable String id, Locale locale) {
        return requests.withdraw(user.userId(), id, locale);
    }

    @Operation(summary = "A short-lived link to download a ready access export")
    @PostMapping("/{id}/download-link")
    Link link(CurrentUser user, @PathVariable String id) {
        return requests.link(user.userId(), id);
    }
}
