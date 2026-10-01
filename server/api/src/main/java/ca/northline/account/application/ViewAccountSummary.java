package ca.northline.account.application;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * The values next to the account menu's items (docs/CONSUMER_WEB_PLAN.md § Account menu,
 * {@code GET /api/v1/me/account-summary}). Every field is optional for the web; S-58 fills the activity ones.
 */
public interface ViewAccountSummary {

    record Summary(
            @Nullable BigDecimal reliability,
            Points points,
            boolean plus,
            int activeOrders,
            int favourites,
            int openCases) {}

    record Points(long balance, long valueCents) {}

    Summary summary(String userId);
}
