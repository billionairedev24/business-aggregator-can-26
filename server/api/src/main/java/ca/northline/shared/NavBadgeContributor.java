package ca.northline.shared;

import java.util.Map;

/**
 * Contributes Studio sidebar badges ({@code GET /api/v1/merchants/{id}/nav-badges}). Each module that owns a screen
 * with a badge implements it as a bean; the {@code studio} module aggregates. Keys are the screen keys of the
 * Studio nav ({@code appointments}, {@code orders}, …); values are the badge text in {@code locale} ("3",
 * "4 to pack"). Return an empty map when there is nothing to show.
 */
public interface NavBadgeContributor {
    Map<String, String> badges(String merchantId, java.util.Locale locale);
}
