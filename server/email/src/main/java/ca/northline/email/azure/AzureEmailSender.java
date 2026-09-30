package ca.northline.email.azure;

import ca.northline.email.EmailAddress;
import ca.northline.email.EmailDeliveryFailed;
import ca.northline.email.EmailMessage;
import ca.northline.email.EmailSender;
import com.azure.identity.DefaultAzureCredentialBuilder;
import java.net.http.HttpClient;
import java.time.Clock;
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
 * Azure Communication Services Email over {@link AzureEmailApi}. The sender is the address only (MailFrom of a
 * verified domain connected to the resource); ACS shows the display name configured for that sender username.
 * Engagement tracking is off per message. HTTP status decides the failure kind
 * ({@link EmailDeliveryFailed#ofHttpStatus}).
 */
@Slf4j
public final class AzureEmailSender implements EmailSender {

    private final AzureEmailApi api;
    private final EmailAddress from;
    private final @Nullable EmailAddress replyTo;

    public AzureEmailSender(AzureEmailApi api, EmailAddress from, @Nullable EmailAddress replyTo) {
        this.api = api;
        this.from = from;
        this.replyTo = replyTo;
    }

    /**
     * @param endpoint {@code https://<resource>.communication.azure.com}
     * @param accessKey the resource's access key (base64), or a connection string {@code
     *     endpoint=…;accesskey=…}; blank = Entra ID via {@code DefaultAzureCredential} (workload identity)
     */
    public static AzureEmailApi client(String endpoint, @Nullable String accessKey, Clock clock) {
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build());
        requests.setReadTimeout(Duration.ofSeconds(15));
        AzureAuthentication auth = accessKey == null || accessKey.isBlank()
                ? new AzureAuthentication.EntraId(new DefaultAzureCredentialBuilder().build())
                : AzureAuthentication.AccessKey.of(keyOf(accessKey), clock);
        var rest = RestClient.builder()
                .baseUrl(stripSlash(endpoint))
                .requestFactory(requests)
                .requestInterceptor(auth)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(AzureEmailApi.class);
    }

    @Override
    public void send(EmailMessage message) {
        var email = new AzureEmailApi.Email(
                from.address(),
                new AzureEmailApi.Content(message.subject(), message.text(), message.html()),
                new AzureEmailApi.Recipients(List.of(address(message.to()))),
                replyTo == null ? null : List.of(address(replyTo)),
                message.headers(),
                true);
        try {
            var response = api.send(AzureEmailApi.API_VERSION, email);
            log.debug(
                    "Azure Communication Services accepted '{}' ({})",
                    message.tag(),
                    response.getHeaders().getFirst("Operation-Location"));
        } catch (RestClientResponseException e) {
            var status = e.getStatusCode().value();
            throw new EmailDeliveryFailed(
                    EmailDeliveryFailed.ofHttpStatus(status),
                    "Azure Communication Services " + status + ": " + e.getResponseBodyAsString(),
                    e);
        } catch (ResourceAccessException e) {
            throw EmailDeliveryFailed.unavailable("Azure Communication Services unreachable: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw EmailDeliveryFailed.unavailable("Azure Communication Services call failed: " + e.getMessage(), e);
        }
    }

    /** The key alone, or the {@code accesskey=} part of a connection string. */
    static String keyOf(String accessKeyOrConnectionString) {
        for (var part : accessKeyOrConnectionString.split(";")) {
            var kv = part.strip();
            if (kv.regionMatches(true, 0, "accesskey=", 0, "accesskey=".length())) {
                return kv.substring("accesskey=".length());
            }
        }
        return accessKeyOrConnectionString.strip();
    }

    private static String stripSlash(String url) {
        var u = url.strip();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static AzureEmailApi.Address address(EmailAddress address) {
        return new AzureEmailApi.Address(address.address(), address.name());
    }
}
