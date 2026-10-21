package ca.northline.email.resend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Resend REST API — only {@code POST /emails} (200 with {@code {"id": …}}), as documented in Resend's OpenAPI
 * description (github.com/resend/resend-openapi, {@code resend.yaml}: {@code emails/send}, {@code SendEmailRequest})
 * and resend.com/docs/api-reference/emails/send-email.
 */
public interface ResendApi {

    /**
     * @param idempotencyKey at most 256 characters; a retry with the same key within 24 hours returns the first 2xx
     *     answer instead of sending again (resend.com/docs/dashboard/emails/idempotency-keys)
     */
    @PostExchange("/emails")
    Sent send(
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestBody Email email);

    /** {@code from}/{@code to}/{@code reply_to} take {@code address} or {@code Name <address>}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Email(
            String from,
            List<String> to,
            String subject,
            String html,
            String text,
            @JsonProperty("reply_to") @Nullable List<String> replyTo,
            @Nullable Map<String, String> headers,
            List<Tag> tags) {}

    /** Name and value: ASCII letters, digits, {@code _} and {@code -} only, at most 256 characters each. */
    record Tag(String name, String value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Sent(@Nullable String id) {}

    /** The error body: {@code {"statusCode": 422, "name": "validation_error", "message": "…"}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Failure(
            @Nullable Integer statusCode,
            @Nullable String name,
            @Nullable String message) {}
}
