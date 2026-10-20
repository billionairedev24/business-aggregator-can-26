package ca.northline.messaging.web;

import ca.northline.messaging.application.InboundSms;
import ca.northline.platform.SmsProperties;
import ca.northline.sms.SmsWebhooks;
import io.swagger.v3.oas.annotations.Operation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * S-157: the SMS provider's webhooks — authenticated by the provider's signature, not a token (Twilio: {@code
 * X-Twilio-Signature} over the public URL configured in Twilio, {@code API_PUBLIC_URL} + the path; the {@code local}
 * fake accepts unsigned posts under the local and test profiles only).
 *
 * <pre>
 * POST /api/v1/webhooks/sms/inbound   form: MessageSid, From, Body   → 200 TwiML (the reply, or an empty Response)
 * POST /api/v1/webhooks/sms/status    form: MessageSid, MessageStatus, ErrorCode?   → 204
 * </pre>
 *
 * 403 {@code invalid_signature} for a delivery that isn't the provider's; 503 {@code sms_webhooks_unconfigured} for a
 * provider without webhooks (AWS / Azure: not built). Retries of a text are answered with an empty Response (applied
 * once). No number or text is logged or stored.
 */
@Slf4j
@RestController
class SmsWebhookController {

    static final String INBOUND = "/api/v1/webhooks/sms/inbound";
    static final String STATUS = "/api/v1/webhooks/sms/status";
    static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final Pattern SID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> STATUSES = Set.of(
            "accepted",
            "scheduled",
            "queued",
            "sending",
            "sent",
            "delivered",
            "undelivered",
            "failed",
            "canceled",
            "read");

    private final Optional<SmsWebhooks.Verifier> verifier;
    private final InboundSms inbound;
    private final String publicBase;

    SmsWebhookController(
            SmsProperties sms,
            Environment environment,
            InboundSms inbound,
            @Value("${northline.sms.webhook-base-url:http://localhost:8080}") String publicBase) {
        this.verifier = SmsWebhooks.of(sms, environment);
        this.inbound = inbound;
        this.publicBase = publicBase.endsWith("/") ? publicBase.substring(0, publicBase.length() - 1) : publicBase;
    }

    @Operation(summary = "A text sent to Northline's number (STOP / START / HELP), from the SMS provider")
    @PostMapping(path = INBOUND, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<?> inbound(
            @RequestParam MultiValueMap<String, String> form,
            @RequestHeader(name = "X-Twilio-Signature", required = false) @Nullable String signature) {
        var refused = refuse(INBOUND, form, signature);
        if (refused != null) {
            return refused;
        }
        var sid = form.getFirst("MessageSid");
        var from = form.getFirst("From");
        if (sid == null || !SID.matcher(sid).matches() || from == null) {
            return ResponseEntity.badRequest().build();
        }
        var reply = inbound.received(new InboundSms.Received(
                verifier.orElseThrow().provider(),
                sid,
                from,
                Optional.ofNullable(form.getFirst("Body")).orElse("")));
        var twiml = XML_DECLARATION
                + reply.map(r -> "<Response><Message>" + HtmlUtils.htmlEscape(r, "UTF-8") + "</Message></Response>")
                        .orElse("<Response/>");
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8))
                .body(twiml);
    }

    @Operation(summary = "The delivery status of a text Northline sent, from the SMS provider")
    @PostMapping(path = STATUS, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<?> status(
            @RequestParam MultiValueMap<String, String> form,
            @RequestHeader(name = "X-Twilio-Signature", required = false) @Nullable String signature) {
        var refused = refuse(STATUS, form, signature);
        if (refused != null) {
            return refused;
        }
        var sid = form.getFirst("MessageSid");
        var status = Optional.ofNullable(form.getFirst("MessageStatus")).orElse("");
        if (sid == null || !SID.matcher(sid).matches() || !STATUSES.contains(status)) {
            return ResponseEntity.badRequest().build();
        }
        var error = form.getFirst("ErrorCode");
        inbound.status(new InboundSms.Status(
                verifier.orElseThrow().provider(),
                sid,
                status,
                error == null || error.isBlank() || error.length() > 16 ? null : error.strip()));
        return ResponseEntity.noContent().build();
    }

    private @Nullable ResponseEntity<?> refuse(
            String path, MultiValueMap<String, String> form, @Nullable String signature) {
        if (verifier.isEmpty()) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "sms_webhooks_unconfigured", "SMS webhooks aren't set up.");
        }
        if (!verifier.get().verified(publicBase + path, Map.<String, List<String>>copyOf(form), signature)) {
            log.warn("SMS webhook {} refused: bad or missing signature", path);
            return problem(HttpStatus.FORBIDDEN, "invalid_signature", "The signature doesn't match.");
        }
        return null;
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        var p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setProperty("code", code);
        return ResponseEntity.status(status).body(p);
    }
}
