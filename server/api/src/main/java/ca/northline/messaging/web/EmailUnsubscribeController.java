package ca.northline.messaging.web;

import ca.northline.email.EmailLocales;
import ca.northline.email.EmailTemplates;
import ca.northline.messaging.application.NotificationLinks;
import ca.northline.messaging.application.UnsubscribeFromEmails;
import ca.northline.messaging.application.UnsubscribeFromEmails.Subscription;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unsubscribe link of notification emails (public: no session — the token is the authorisation):
 *
 * <pre>
 * GET  /api/v1/email/unsubscribe?t=…   confirmation page with an "Unsubscribe" button (GET never changes anything:
 *                                      mail scanners prefetch links)
 * POST /api/v1/email/unsubscribe?t=…   turns the email off; also RFC 8058 one-click (body List-Unsubscribe=One-Click)
 * </pre>
 *
 * Both answer a small HTML page in the member's language; an invalid token is 400 with a pointer to Settings.
 */
@RestController
@RequestMapping(NotificationLinks.UNSUBSCRIBE_PATH)
@RequiredArgsConstructor
class EmailUnsubscribeController {

    private final UnsubscribeFromEmails unsubscribe;
    private final EmailTemplates templates;

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> confirm(
            @RequestParam(name = "t", required = false) @Nullable String token,
            @RequestHeader(name = "Accept-Language", required = false) @Nullable String language) {
        var found = token == null ? Optional.<Subscription>empty() : unsubscribe.check(token);
        return page(found, "confirm", token, language);
    }

    @PostMapping(produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> unsubscribe(
            @RequestParam(name = "t", required = false) @Nullable String token,
            @RequestHeader(name = "Accept-Language", required = false) @Nullable String language) {
        var done = token == null ? Optional.<Subscription>empty() : unsubscribe.unsubscribe(token);
        return page(done, "done", token, language);
    }

    private ResponseEntity<String> page(
            Optional<Subscription> subscription, String state, @Nullable String token, @Nullable String language) {
        var locale = subscription.map(Subscription::locale).orElseGet(() -> fromHeader(language));
        var variables = new HashMap<String, Object>();
        variables.put("state", subscription.isPresent() ? state : "invalid");
        subscription.ifPresent(s -> {
            variables.put("eventLabel", templates.message(locale, "event." + s.event(), List.of()));
            variables.put("token", token == null ? "" : token);
            variables.put("action", NotificationLinks.UNSUBSCRIBE_PATH);
        });
        return ResponseEntity.status(subscription.isPresent() ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex")
                .body(templates.page("unsubscribe", locale, variables));
    }

    private static Locale fromHeader(@Nullable String language) {
        if (language == null || language.isBlank()) {
            return EmailLocales.ENGLISH;
        }
        var ranges = Locale.LanguageRange.parse(language);
        return ranges.isEmpty()
                ? EmailLocales.ENGLISH
                : EmailLocales.of(ranges.getFirst().getRange());
    }
}
