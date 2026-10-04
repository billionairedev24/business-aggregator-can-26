package ca.northline.payments.api;

/**
 * S-118: whether this deployment is set up to take real money — the go-live checklist's "Stripe live mode" gate. Read
 * from configuration only (key prefixes and whether the webhook signing secrets are set); never calls Stripe and never
 * returns a key.
 */
public interface StripeMode {

    Mode mode();

    /**
     * @param secretKey {@code live} ({@code sk_live_…} / {@code rk_live_…}), {@code test}, or {@code none} (the fake
     *     gateway)
     * @param publishableKey {@code live} ({@code pk_live_…}), {@code test} or {@code none}
     * @param webhookSecret the platform webhook endpoint's signing secret is set
     * @param connectWebhookSecret the Connect webhook endpoint's signing secret is set
     */
    record Mode(String secretKey, String publishableKey, boolean webhookSecret, boolean connectWebhookSecret) {

        public boolean live() {
            return "live".equals(secretKey) && "live".equals(publishableKey) && webhookSecret && connectWebhookSecret;
        }
    }
}
