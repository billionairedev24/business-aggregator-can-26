package ca.northline.worker.webhooks;

import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Outbound port: one HTTPS POST to a partner endpoint. Adapter: {@link HttpWebhookTransport}. Never throws. */
public interface WebhookTransport {

    Result post(URI url, Map<String, String> headers, byte[] body);

    /**
     * What happened.
     *
     * @param status the HTTP status, null when no answer came back (refused address, DNS, connect, TLS, timeout)
     * @param error why it failed, in a few words for the delivery log; null on success
     * @param snippet the first characters of the answer (control characters removed), null when there was none
     */
    record Result(
            @Nullable Integer status,
            int durationMs,
            @Nullable String error,
            @Nullable String snippet) {

        public boolean success() {
            return status != null && status >= 200 && status < 300;
        }

        /** The short outcome for logs and the owner's email: "HTTP 503", "timed out", … */
        public String outcome() {
            if (error != null) {
                return error;
            }
            return status == null ? "no answer" : "HTTP " + status;
        }
    }
}
