package ca.northline.email.ses;

import ca.northline.email.EmailAddress;
import ca.northline.email.EmailDeliveryFailed;
import ca.northline.email.EmailMessage;
import ca.northline.email.EmailSender;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.MessageHeader;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.MessageTag;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/**
 * Amazon SES API v2 {@code SendEmail} (simple content: subject, text, HTML, custom headers such as
 * {@code List-Unsubscribe}). Credentials come from the SDK's default chain (workload identity: IRSA / EKS Pod
 * Identity); needs {@code ses:SendEmail} on the verified identity (and the configuration set, when used).
 *
 * <p>{@code MessageRejected} and {@code BadRequest} reject the message; everything else — throttling, sending paused,
 * account suspended, unverified MAIL FROM, missing configuration set, 5xx, network — is unavailable.
 */
@Slf4j
public final class SesEmailSender implements EmailSender, AutoCloseable {

    private final SesV2Client client;
    private final EmailAddress from;
    private final @Nullable EmailAddress replyTo;
    private final @Nullable String configurationSet;

    public SesEmailSender(
            SesV2Client client, EmailAddress from, @Nullable EmailAddress replyTo, @Nullable String configurationSet) {
        this.client = client;
        this.from = from;
        this.replyTo = replyTo;
        this.configurationSet = configurationSet == null || configurationSet.isBlank() ? null : configurationSet;
    }

    /** SDK client without SDK retries ({@code RetryingEmailSender} is the one retry policy) and a 15 s call limit. */
    public static SesV2Client client(@Nullable String region, @Nullable String endpoint) {
        var builder = SesV2Client.builder()
                .overrideConfiguration(
                        o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()).apiCallTimeout(Duration.ofSeconds(15)));
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region.strip()));
        }
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.strip()));
        }
        return builder.build();
    }

    @Override
    public void send(EmailMessage message) {
        var headers = message.headers().entrySet().stream()
                .map(h -> MessageHeader.builder()
                        .name(h.getKey())
                        .value(h.getValue())
                        .build())
                .toList();
        var request = SendEmailRequest.builder()
                .fromEmailAddress(from.toInternetAddress().toString())
                .destination(Destination.builder()
                        .toAddresses(message.to().toInternetAddress().toString())
                        .build())
                .content(c -> c.simple(Message.builder()
                        .subject(utf8(message.subject()))
                        .body(Body.builder()
                                .text(utf8(message.text()))
                                .html(utf8(message.html()))
                                .build())
                        .headers(headers)
                        .build()))
                .emailTags(MessageTag.builder()
                        .name("template")
                        .value(message.tag())
                        .build());
        if (replyTo != null) {
            request.replyToAddresses(replyTo.toInternetAddress().toString());
        }
        if (configurationSet != null) {
            request.configurationSetName(configurationSet);
        }
        try {
            var id = client.sendEmail(request.build()).messageId();
            log.debug("SES accepted '{}' as {}", message.tag(), id);
        } catch (MessageRejectedException | BadRequestException e) {
            throw new EmailDeliveryFailed(
                    EmailDeliveryFailed.Kind.REJECTED, "SES rejected the message: " + detail(e), e);
        } catch (SesV2Exception e) {
            throw EmailDeliveryFailed.unavailable("SES " + e.statusCode() + ": " + detail(e), e);
        } catch (SdkException e) {
            throw EmailDeliveryFailed.unavailable("SES unreachable: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private static Content utf8(String data) {
        return Content.builder()
                .data(data)
                .charset(StandardCharsets.UTF_8.name())
                .build();
    }

    private static String detail(SesV2Exception e) {
        var details = e.awsErrorDetails();
        return details == null ? String.valueOf(e.getMessage()) : details.errorCode() + " " + details.errorMessage();
    }
}
