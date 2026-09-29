package ca.northline.shared;

import ca.northline.shared.security.MerchantRole;
import java.util.Locale;
import java.util.Map;

/**
 * A module's contribution to the Studio sidebar badges ({@code GET /api/v1/merchants/{id}/nav-badges}): screen key
 * (as in the Studio's {@code nav.ts}) → badge text in the caller's locale. The {@code studio} module aggregates every
 * contributor bean.
 */
public interface NavBadgeContributor {

    /** Who is asking, for which business, in which language. */
    record Context(String merchantId, String userId, MerchantRole role, Locale locale) {
        public boolean french() {
            return "fr".equals(locale.getLanguage());
        }
    }

    Map<String, String> badges(Context context);
}
