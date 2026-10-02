package ca.northline.messaging.web;

import ca.northline.messaging.application.Consents;
import ca.northline.messaging.application.Consents.ManageConsents;
import ca.northline.messaging.domain.ConsentCategory;
import ca.northline.messaging.domain.ConsentEvidence;
import ca.northline.messaging.domain.ConsentRecord;
import ca.northline.messaging.domain.ConsentSource;
import ca.northline.messaging.domain.ConsentWordings;
import ca.northline.messaging.domain.CustomerNotificationPrefs;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.CurrentUser;
import ca.northline.shared.web.ClientAddress;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's CASL consents to Northline's commercial messages (S-108): the state per category with the wording to
 * read, and the full history — grants and withdrawals, when, where — for the account area (web, app, Studio).
 *
 * <pre>
 * GET /api/v1/me/consents[?surface=account|studio]           {categories, history, requester}
 * PUT /api/v1/me/consents/{category}  {granted, source, wordingVersion?}   grant or withdraw, at once
 * </pre>
 *
 * {@code category}: {@code marketing_email | marketing_sms | marketing_push}. A grant records the wording version
 * shown, the language, the truncated IP address and the hashed user agent; a stale wording is 409
 * {@code consent_wording_changed}. The Account › Notifications settings ({@code /me/notifications}) change the same
 * consents through the {@code offers} row and the marketing-email choice.
 */
@RestController
@RequestMapping("/api/v1/me/consents")
@RequiredArgsConstructor
class MyConsentsController {

    static final String SOURCES = "web_signup|app_signup|web_settings|app_settings|checkout|studio";

    private final ManageConsents consents;

    record CategoryView(
            String category,
            String channel,
            boolean granted,
            @Nullable Instant since,
            @Nullable String source,
            String wordingVersion,
            String wording) {}

    record HistoryItem(
            String id,
            String category,
            String action,
            Instant at,
            String source,
            @Nullable String wordingVersion,
            @Nullable String language) {

        static HistoryItem of(ConsentRecord r) {
            return new HistoryItem(
                    r.id(),
                    r.category().code(),
                    r.granted() ? "granted" : "withdrawn",
                    r.at(),
                    r.source().code(),
                    r.wordingVersion(),
                    r.language());
        }
    }

    record ConsentsResponse(
            List<CategoryView> categories,
            List<HistoryItem> history,

            @Schema(description = "Who asks for consent: legal name, mailing address and contact (CASL)")
            String requester) {

        static ConsentsResponse of(Consents.View v) {
            return new ConsentsResponse(
                    v.categories().stream()
                            .map(c -> new CategoryView(
                                    c.category().code(),
                                    c.category().channel(),
                                    c.granted(),
                                    c.since(),
                                    c.source() == null ? null : c.source().code(),
                                    c.wordingVersion(),
                                    c.wording()))
                            .toList(),
                    v.history().stream().map(HistoryItem::of).toList(),
                    v.requester());
        }
    }

    record ConsentRequest(
            @NotNull(message = CustomerNotificationPrefs.CHOOSE)
            Boolean granted,

            @NotNull(message = CustomerNotificationPrefs.CHOOSE)
            @Pattern(regexp = SOURCES, message = CustomerNotificationPrefs.CHOOSE)
            String source,

            @Nullable
            @Schema(description = "The wording version the person read; omitted = the current one")
            @Pattern(regexp = "[a-z0-9_.-]{3,64}", message = CustomerNotificationPrefs.CHOOSE)
            String wordingVersion) {}

    @Operation(summary = "The caller's consents to marketing messages, with the wording and the history")
    @GetMapping
    ResponseEntity<ConsentsResponse> get(
            CurrentUser user,
            @RequestParam(defaultValue = "account") @Pattern(regexp = "account|studio") String surface,
            Locale locale) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ConsentsResponse.of(consents.view(user.userId(), surface(surface), language(locale))));
    }

    @Operation(summary = "Give or withdraw one consent to marketing messages")
    @PutMapping("/{category}")
    ConsentsResponse put(
            CurrentUser user,
            @PathVariable String category,
            @Valid @RequestBody ConsentRequest body,
            Locale locale,
            HttpServletRequest request) {
        var which = Arrays.stream(ConsentCategory.values())
                .filter(c -> c.code().equals(category))
                .findFirst()
                .orElseThrow(() -> new NotFound("consent category", category));
        var source = CodedEnum.fromCode(ConsentSource.class, body.source());
        consents.change(
                user.userId(),
                new Consents.Change(
                        which, body.granted(), source, body.wordingVersion(), language(locale), evidence(request)));
        var surface = source == ConsentSource.STUDIO ? ConsentWordings.Surface.STUDIO : ConsentWordings.Surface.ACCOUNT;
        return ConsentsResponse.of(consents.view(user.userId(), surface, language(locale)));
    }

    static ConsentEvidence evidence(HttpServletRequest request) {
        return ConsentEvidence.of(ClientAddress.of(request), request.getHeader(HttpHeaders.USER_AGENT));
    }

    static String language(Locale locale) {
        return "fr".equals(locale.getLanguage()) ? "fr" : "en";
    }

    private static ConsentWordings.Surface surface(String value) {
        return "studio".equals(value) ? ConsentWordings.Surface.STUDIO : ConsentWordings.Surface.ACCOUNT;
    }
}
