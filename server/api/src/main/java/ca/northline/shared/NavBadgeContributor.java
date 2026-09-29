package ca.northline.shared;

import java.util.Map;

/**
 * A module's contribution to the Studio sidebar badges ({@code GET /api/v1/merchants/{id}/nav-badges}): screen key (as
 * in the studio's {@code nav.ts}) → badge text, computed in the caller's locale. The {@code studio} module aggregates
 * every bean implementing it.
 */
public interface NavBadgeContributor {
    Map<String, String> badges(String merchantId, java.util.Locale locale);
}
