package ca.northline.account.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The values next to the account menu's items (docs/CONSUMER_WEB_PLAN.md § Account menu,
 * {@code GET /api/v1/me/account-summary}). Every field is optional for the web; S-58 fills the activity ones.
 */
public interface ViewAccountSummary {

    /**
     * @param signIn {@code passkey | totp | sms}
     * @param quietHours "10 pm" / "22 h" in the reader's language, null when quiet hours are off
     * @param dietary the dietary choices in the reader's language ("Halal")
     * @param province the province chosen under Language &amp; region, else the default address's
     */
    record Summary(
            @Nullable BigDecimal reliability,
            Points points,
            boolean plus,
            int activeOrders,
            int favourites,
            int openCases,
            @Nullable Card paymentMethod,
            Addresses addresses,
            @Nullable String signIn,
            @Nullable Quiet quietHours,
            List<String> dietary,
            @Nullable String province) {

        public Summary {
            dietary = List.copyOf(dietary);
        }
    }

    record Points(long balance, long valueCents) {}

    record Card(String brand, String last4) {}

    record Addresses(int count, int members) {}

    record Quiet(String from, String to) {}

    Summary summary(String userId, Locale locale);
}
