package ca.northline.payments.infra;

import ca.northline.payments.api.StripeMode;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** {@link StripeMode} from {@code northline.payments.*}: prefixes only, the keys themselves never leave this class. */
@Component
@RequiredArgsConstructor
class ConfiguredStripeMode implements StripeMode {

    private final PaymentsProperties props;

    @Override
    public Mode mode() {
        return new Mode(
                kind(props.stripeSecretKey(), "sk_live_", "rk_live_"),
                kind(props.stripePublishableKey(), "pk_live_", "pk_live_"),
                set(props.stripeWebhookSecret()),
                set(props.stripeConnectWebhookSecret()));
    }

    private static String kind(@Nullable String key, String live, String restrictedLive) {
        if (key == null || key.isBlank()) {
            return "none";
        }
        return key.startsWith(live) || key.startsWith(restrictedLive) ? "live" : "test";
    }

    private static boolean set(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
