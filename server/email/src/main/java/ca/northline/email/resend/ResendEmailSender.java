package ca.northline.email.resend;

import ca.northline.email.EmailAddress;
import ca.northline.email.EmailDeliveryFailed;
import ca.northline.email.EmailMessage;
import ca.northline.email.EmailSender;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Resend ({@code POST https://api.resend.com/emails}) over {@link ResendApi}, authenticated with a Bearer API key
 * ({@code re_…}, "Sending access" is enough). HTTPS only, so it works where outbound SMTP ports are filtered. Each
 * message carries an {@code Idempotency-Key} derived from the Mailer's delivery key, so the retry policy and later
 * re-attempts never send it twice within Resend's 24-hour window. HTTP status decides the failure kind
 * ({@link EmailDeliveryFailed#ofHttpStatus}); a 409 is a concurrent request with the same key (unavailable: retried) or
 * the key already used for this message (rejected: it was accepted before).
 */
@Slf4j
public final class ResendEmailSender implements EmailSender {

    public static final String API = "https://api.resend.com";

    private static final Pattern ADDRESS = Pattern.compile("[^\\s<>\"'(),;:`]+@[A-Za-z0-9.-]+");
    private static final Pattern NOT_TAG = Pattern.compile("[^A-Za-z0-9_-]");

    private final ResendApi api;
    private final String from;
    private final @Nullable List<String> replyTo;

    public ResendEmailSender(ResendApi api, EmailAddress from, @Nullable EmailAddress replyTo) {
        this.api = api;
        this.from = mailbox(from);
        this.replyTo = replyTo == null ? null : List.of(mailbox(replyTo));
    }

    /** {@code @HttpExchange} client on the JDK HTTP client (connect 5 s, read 15 s). */
    public static ResendApi client(@Nullable String endpoint, String apiKey) {
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build());
        requests.setReadTimeout(Duration.ofSeconds(15));
        var rest = RestClient.builder()
                .baseUrl(endpoint == null || endpoint.isBlank() ? API : endpoint.strip())
                .defaultHeaders(h -> h.setBearerAuth(apiKey))
                .defaultHeader("User-Agent", "northline-email")
                .requestFactory(requests)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(ResendApi.class);
    }

    @Override
    public void send(EmailMessage message) {
        var email = new ResendApi.Email(
                from,
                List.of(mailbox(message.to())),
                message.subject(),
                message.html(),
                message.text(),
                replyTo,
                message.headers().isEmpty() ? null : message.headers(),
                List.of(new ResendApi.Tag(
                        "template", NOT_TAG.matcher(message.tag()).replaceAll("_"))));
        try {
            var sent = api.send(idempotencyKey(message.idempotencyKey()), email);
            log.debug("Resend accepted '{}' as {}", message.tag(), sent == null ? null : sent.id());
        } catch (RestClientResponseException e) {
            var status = e.getStatusCode().value();
            var error = error(e);
            var name = error == null || error.name() == null ? "" : error.name();
            var detail = error == null || error.message() == null ? e.getResponseBodyAsString() : error.message();
            var kind = status == 409
                    ? ("concurrent_idempotent_requests".equals(name)
                            ? EmailDeliveryFailed.Kind.UNAVAILABLE
                            : EmailDeliveryFailed.Kind.REJECTED)
                    : EmailDeliveryFailed.ofHttpStatus(status);
            throw new EmailDeliveryFailed(
                    kind, "Resend " + status + (name.isEmpty() ? "" : " " + name) + ": " + masked(detail), e);
        } catch (ResourceAccessException e) {
            throw EmailDeliveryFailed.unavailable("Resend unreachable: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw EmailDeliveryFailed.unavailable("Resend call failed: " + e.getMessage(), e);
        }
    }

    /** The Mailer's delivery key, hashed: fixed length (Resend allows 256) and no internal ids leave the platform. */
    public static @Nullable String idempotencyKey(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "northline-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Provider messages may quote an address (e.g. the account owner's in test mode): masked like every email log. */
    static String masked(String text) {
        return ADDRESS.matcher(text).replaceAll(m -> EmailAddress.of(m.group()).masked());
    }

    private static ResendApi.@Nullable Failure error(RestClientResponseException e) {
        try {
            return e.getResponseBodyAs(ResendApi.Failure.class);
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    /** {@code address} or {@code "Name" <address>} (quoted, so commas or dots in a name never split it). */
    private static String mailbox(EmailAddress address) {
        var name = address.name();
        if (name == null) {
            return address.address();
        }
        return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\" <" + address.address() + ">";
    }
}
