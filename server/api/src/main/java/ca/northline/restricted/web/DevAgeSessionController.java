package ca.northline.restricted.web;

import ca.northline.restricted.application.DevAgeOutcomes;
import ca.northline.shared.NotFound;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * LOCAL PROFILE ONLY, with {@code AGE_VERIFICATION_PROVIDER=local}: the fake ID check's "hosted flow". The consumer site
 * or app opens {@code GET /api/v1/dev/age-sessions/{id}}; the developer picks an outcome (an age, under age, an ID
 * error), applied as the provider's webhook would be, and the browser goes back to the return URL. Public like the
 * real flow (the session id is the secret); 404 when the real adapter is configured.
 */
@Profile("local")
@RestController
@RequestMapping("/api/v1/dev/age-sessions/{sessionId}")
@RequiredArgsConstructor
class DevAgeSessionController {

    private final ObjectProvider<DevAgeOutcomes> outcomes;

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    String page(@PathVariable String sessionId) {
        var fake = fake();
        var id = HtmlUtils.htmlEscape(sessionId);
        var buttons = new StringBuilder();
        for (var o : fake.outcomes()) {
            buttons.append("<button name=\"outcome\" value=\"%s\">%s</button>\n".formatted(o, o.replace('_', ' ')));
        }
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" \
                content="width=device-width,initial-scale=1"><title>Fake ID check</title>
                <style>body{font:16px system-ui,sans-serif;max-width:32rem;margin:2rem auto;padding:0 1rem}
                button{display:block;width:100%%;min-height:44px;margin:.4rem 0;font:inherit;cursor:pointer}</style>
                </head><body><h1>Fake ID check</h1>
                <p>Local development only. Session <code>%s</code>. Pick what the ID and selfie show; it is applied as
                the provider's webhook would be.</p>
                <form method="post" action="/api/v1/dev/age-sessions/%s">
                %s</form></body></html>
                """.formatted(id, id, buttons);
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> finish(@PathVariable String sessionId, @RequestParam String outcome) {
        var back = fake().finish(sessionId, outcome).orElseThrow(() -> new NotFound("age session", sessionId));
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(back))
                .build();
    }

    private DevAgeOutcomes fake() {
        var fake = outcomes.getIfAvailable();
        if (fake == null) {
            throw new NotFound("age session", "fake");
        }
        return fake;
    }
}
