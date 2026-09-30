package ca.northline.messaging.web;

import ca.northline.email.EmailContent;
import ca.northline.email.EmailLocales;
import ca.northline.email.EmailTemplates;
import ca.northline.shared.NotFound;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local-only preview of every email template with sample data (profile {@code local}; the route doesn't exist
 * elsewhere):
 *
 * <pre>
 * GET /api/v1/dev/emails                                     {items: [{key, subjectEn, subjectFr, html, text}]}
 * GET /api/v1/dev/emails/{key}?lang=fr-CA[&amp;format=text]    the rendered HTML (or text)
 * </pre>
 *
 * Open {@code http://localhost:8080/api/v1/dev/emails/payout-sent?lang=fr-CA} in a browser.
 */
@Profile("local")
@RestController
@RequestMapping("/api/v1/dev/emails")
@RequiredArgsConstructor
class EmailPreviewController {

    private static final URI SAMPLE_UNSUBSCRIBE = URI.create("http://localhost:8080/api/v1/email/unsubscribe?t=sample");

    private final EmailTemplates templates;

    record Preview(String key, String subjectEn, String subjectFr, String html, String text) {}

    record Previews(List<Preview> items) {}

    @GetMapping
    Previews list() {
        return new Previews(EmailContent.samples().entrySet().stream()
                .map(e -> new Preview(
                        e.getKey(),
                        templates
                                .render(e.getValue(), EmailLocales.ENGLISH, SAMPLE_UNSUBSCRIBE)
                                .subject(),
                        templates
                                .render(e.getValue(), EmailLocales.FRENCH, SAMPLE_UNSUBSCRIBE)
                                .subject(),
                        "/api/v1/dev/emails/" + e.getKey() + "?lang=en-CA",
                        "/api/v1/dev/emails/" + e.getKey() + "?lang=en-CA&format=text"))
                .toList());
    }

    @GetMapping("/{key}")
    ResponseEntity<String> render(
            @PathVariable String key,
            @RequestParam(defaultValue = "en-CA") String lang,
            @RequestParam(defaultValue = "html") String format) {
        var content = EmailContent.samples().get(key);
        if (content == null) {
            throw new NotFound("email template", key);
        }
        var email = templates.render(content, EmailLocales.of(lang), SAMPLE_UNSUBSCRIBE);
        var text = "text".equals(format);
        return ResponseEntity.ok()
                .contentType(new MediaType(text ? MediaType.TEXT_PLAIN : MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header("X-Email-Subject", java.net.URLEncoder.encode(email.subject(), StandardCharsets.UTF_8))
                .body(text ? "Subject: " + email.subject() + "\n\n" + email.text() : email.html());
    }
}
