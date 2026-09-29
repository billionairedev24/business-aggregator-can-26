package ca.northline.shared;

import ca.northline.shared.security.MerchantRole;
import java.util.Locale;
import java.util.Map;

/**
 * Contributes Studio sidebar badges ({@code GET /api/v1/merchants/{id}/nav-badges}, aggregated by the {@code studio}
 * module). Implement it as a Spring bean in your module and return badge texts keyed by the screen keys of
 * {@code web/apps/studio/src/features/shell/nav.ts}: {@code dashboard, appointments, orders, messages, products,
 * availability, storefront, earnings, reports, payouts, refunds, compliance, reviews, settings, help, kds, menu,
 * combos, kitchenHours}. Omit a key (or return an empty map) for "no badge".
 *
 * <p>Texts are final display strings in {@link Context#locale()} ("3", "4 to pack" / "4 à emballer", "Fri" / "ven.").
 * Keep it cheap (one indexed query); it runs on every Studio page load. Use {@link Context#role()} /
 * {@link Context#userId()} when a badge is personal (a technician's own jobs).
 */
public interface NavBadgeContributor {

    /** Who is asking, about which business, in which language. */
    record Context(String merchantId, String userId, MerchantRole role, Locale locale) {
        public boolean french() {
            return locale.getLanguage().equals("fr");
        }
    }

    Map<String, String> badges(Context context);
}
