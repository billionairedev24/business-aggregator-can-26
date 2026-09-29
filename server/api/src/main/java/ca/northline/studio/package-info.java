/**
 * Studio composition: endpoints that aggregate other modules for the Studio shell — the sidebar badges
 * ({@code GET /api/v1/merchants/{merchantId}/nav-badges}, from every {@link ca.northline.shared.NavBadgeContributor}
 * bean) and, later, the dashboard. Reads other modules only through their public API / shared contracts.
 */
@ApplicationModule(displayName = "studio")
@NullMarked
package ca.northline.studio;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
