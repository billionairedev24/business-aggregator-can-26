package ca.northline.studio.application;

import ca.northline.shared.NavBadgeContributor;
import java.util.Map;

/** Sidebar badges of one business for the caller: screen key → badge text. */
public interface NavBadges {
    Map<String, String> of(NavBadgeContributor.Context context);
}
