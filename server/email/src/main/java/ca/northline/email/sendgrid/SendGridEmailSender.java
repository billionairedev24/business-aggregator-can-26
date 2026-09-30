package ca.northline.email.sendgrid;

import ca.northline.email.EmailAddress;
import ca.northline.email.EmailDeliveryFailed;
import ca.northline.email.EmailMessage;
import ca.northline.email.EmailSender;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
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
 * Twilio SendGrid v3 Mail Send over {@link SendGridApi} (Bearer API key with the "Mail Send" permission only). Link and
 * open tracking are turned off per message. HTTP status decides the failure kind
 * ({@link EmailDeliveryFailed#ofHttpStatus}).
 */
@Slf4j
public final class SendGridEmailSender implements EmailSender {

    public static final String API = "https://api.sendgrid.com";

    private final SendGridApi api;
    private final EmailAddress from;
    private final @Nullable EmailAddress replyTo;

    public SendGridEmailSender(SendGridApi api, EmailAddress from, @Nullable EmailAddress replyTo) {
        this.api = api;
        this.from = from;
        this.replyTo = replyTo;
    }

    /** {@code @HttpExchange} client on the JDK HTTP client (connect 5 s, read 15 s). */
    public static SendGridApi client(@Nullable String endpoint, String apiKey) {
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build());
        requests.setReadTimeout(Duration.ofSeconds(15));
        var rest = RestClient.builder()
                .baseUrl(endpoint == null || endpoint.isBlank() ? API : endpoint.strip())
                .defaultHeaders(h -> h.setBearerAuth(apiKey))
                .requestFactory(requests)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(SendGridApi.class);
    }

    @Override
    public void send(EmailMessage message) {
        var mail = new SendGridApi.Mail(
                List.of(new SendGridApi.Personalization(List.of(address(message.to())))),
                address(from),
                replyTo == null ? null : address(replyTo),
                message.subject(),
                List.of(
                        new SendGridApi.Content("text/plain", message.text()),
                        new SendGridApi.Content("text/html", message.html())),
                message.headers(),
                List.of(message.tag()),
                SendGridApi.TrackingSettings.OFF);
        try {
            var response = api.send(mail);
            log.debug(
                    "SendGrid accepted '{}' as {}",
                    message.tag(),
                    response.getHeaders().getFirst("X-Message-Id"));
        } catch (RestClientResponseException e) {
            var status = e.getStatusCode().value();
            throw new EmailDeliveryFailed(
                    EmailDeliveryFailed.ofHttpStatus(status),
                    "SendGrid " + status + ": " + e.getResponseBodyAsString(),
                    e);
        } catch (ResourceAccessException e) {
            throw EmailDeliveryFailed.unavailable("SendGrid unreachable: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw EmailDeliveryFailed.unavailable("SendGrid call failed: " + e.getMessage(), e);
        }
    }

    private static SendGridApi.Address address(EmailAddress address) {
        return new SendGridApi.Address(address.address(), address.name());
    }
}
