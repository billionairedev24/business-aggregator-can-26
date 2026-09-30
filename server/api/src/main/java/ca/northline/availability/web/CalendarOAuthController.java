package ca.northline.availability.web;

import ca.northline.availability.application.CalendarUseCases.CompleteCalendarConnection;
import ca.northline.availability.application.CalendarUseCases.CompleteCalendarConnection.Callback;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.CurrentUser;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OAuth redirect URI of the calendar providers (S-32): {@code https://studio.<zone>/api/v1/calendar/oauth/<google|
 * outlook>/callback}. It is on the Studio host, so the browser comes back through the studio-bff with its session and
 * the member is authenticated here; the state must be the one this member started (single use, 10 min). Answers 303
 * to the Availability screen with the outcome ({@code ?calendar=google&result=connected|denied|failed|scopes|expired}).
 */
@RestController
@RequiredArgsConstructor
class CalendarOAuthController {

    private final CompleteCalendarConnection complete;

    @GetMapping("/api/v1/calendar/oauth/{provider}/callback")
    ResponseEntity<Void> callback(
            @PathVariable String provider,
            @RequestParam(required = false) @Nullable String code,
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable String error,
            CurrentUser user) {
        var p = provider(provider);
        var done = complete.complete(new Callback(user.userId(), p, code, state, error));
        var target = UriComponentsBuilder.fromPath(
                        done.merchantId() == null ? "/" : "/b/" + done.merchantId() + "/availability")
                .queryParam("calendar", p.code())
                .queryParam("result", done.outcome().code());
        if (done.choose()) {
            target.queryParam("choose", "1");
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(target.build().encode().toUriString()))
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    private static CalendarProvider provider(String code) {
        try {
            var p = CodedEnum.fromCode(CalendarProvider.class, code);
            if (p.twoWay()) {
                return p;
            }
        } catch (IllegalArgumentException _) {
            // fall through
        }
        throw new NotFound("calendar provider", code);
    }
}
