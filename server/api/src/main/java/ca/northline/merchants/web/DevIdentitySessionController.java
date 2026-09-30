package ca.northline.merchants.web;

import ca.northline.merchants.application.DevIdentityOutcomes;
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
 * LOCAL PROFILE ONLY, with {@code IDENTITY_PROVIDER=local}: the fake Stripe Identity "hosted flow". The Studio (or an
 * emailed link, in Mailpit) opens {@code GET /api/v1/dev/identity-sessions/{id}}; the developer picks an outcome, which
 * is applied like Stripe's webhook, and the browser goes back to the session's return URL. Public like the real flow
 * (the session id is the secret); 404 when the real adapter is configured.
 */
@Profile("local")
@RestController
@RequestMapping("/api/v1/dev/identity-sessions/{sessionId}")
@RequiredArgsConstructor
class DevIdentitySessionController {

    private final ObjectProvider<DevIdentityOutcomes> outcomes;

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
                content="width=device-width,initial-scale=1"><title>Fake Stripe Identity</title>
                <style>body{font:16px system-ui,sans-serif;max-width:32rem;margin:2rem auto;padding:0 1rem}
                button{display:block;width:100%%;min-height:44px;margin:.4rem 0;font:inherit;cursor:pointer}</style>
                </head><body><h1>Fake Stripe Identity</h1>
                <p>Local development only. Session <code>%s</code>. Pick how this verification ends; it is applied as
                Stripe's webhook would be.</p>
                <form method="post" action="/api/v1/dev/identity-sessions/%s">
                %s</form></body></html>
                """.formatted(id, id, buttons);
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> finish(@PathVariable String sessionId, @RequestParam String outcome) {
        var back = fake().finish(sessionId, outcome).orElseThrow(() -> new NotFound("identity session", sessionId));
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(back))
                .build();
    }

    private DevIdentityOutcomes fake() {
        var fake = outcomes.getIfAvailable();
        if (fake == null) {
            throw new NotFound("identity session", "fake");
        }
        return fake;
    }
}
