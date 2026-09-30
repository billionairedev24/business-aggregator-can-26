package ca.northline.email;

/**
 * Outbound port: hands one email to the provider selected by {@code northline.email.provider}. Returns once the
 * provider accepted it (delivery itself is asynchronous at every provider), otherwise throws
 * {@link EmailDeliveryFailed}. The configured bean already retries transient failures.
 */
public interface EmailSender {

    void send(EmailMessage message);
}
