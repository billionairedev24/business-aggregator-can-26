package ca.northline.email.sendgrid;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Twilio SendGrid Web API v3 — only {@code POST /v3/mail/send} (202 Accepted, empty body). */
public interface SendGridApi {

    @PostExchange("/v3/mail/send")
    ResponseEntity<Void> send(@RequestBody Mail mail);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Mail(
            List<Personalization> personalizations,
            Address from,
            @JsonProperty("reply_to") @Nullable Address replyTo,
            String subject,
            List<Content> content,
            Map<String, String> headers,
            List<String> categories,
            @JsonProperty("tracking_settings") TrackingSettings trackingSettings) {}

    record Personalization(List<Address> to) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Address(String email, @Nullable String name) {}

    /** {@code type} = {@code text/plain} (must come first) or {@code text/html}. */
    record Content(String type, String value) {}

    /** Click tracking rewrites links (invitation tokens would pass through SendGrid's redirector): off. */
    record TrackingSettings(
            @JsonProperty("click_tracking") Toggle clickTracking,
            @JsonProperty("open_tracking") Toggle openTracking) {
        static final TrackingSettings OFF = new TrackingSettings(new Toggle(false), new Toggle(false));
    }

    record Toggle(boolean enable) {}
}
