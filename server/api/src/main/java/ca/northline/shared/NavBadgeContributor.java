package ca.northline.shared;

import ca.northline.shared.security.MerchantRole;
import java.util.Locale;
import java.util.Map;

/**
 * A module's contribution to the Studio sidebar badges ({@code GET /api/v1/merchants/{id}/nav-badges}): screen key (as
 * in the studio's {@code nav.ts}) → badge text, computed in the caller's locale. The {@code studio} module aggregates
 * every bean implementing it.
 */
public interface NavBadgeContributor {

    /** Who is asking: badges may depend on the member (a technician's unread count covers only their own jobs). */
    record Context(String merchantId, String userId, MerchantRole role, Locale locale) {
        public boolean french() {
            return "fr".equals(locale.getLanguage());
        }
    }

    Map<String, String> badges(Context context);
}
