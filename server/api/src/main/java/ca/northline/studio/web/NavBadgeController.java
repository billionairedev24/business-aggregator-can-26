package ca.northline.studio.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.shared.NavBadgeContributor;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import ca.northline.studio.application.NavBadges;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/merchants/{merchantId}/nav-badges} → {@code {"<screenKey>": "<badge text>"}} in the caller's
 * {@code Accept-Language} (IMPLEMENTATION_PLAN.md › Nav badges). A plain object, not {@code {"items": …}}: it is a
 * map keyed by screen, and the Studio shell already consumes this shape.
 */
@RestController
@RequiredArgsConstructor
class NavBadgeController {

    private final NavBadges navBadges;

    @GetMapping("/api/v1/merchants/{merchantId}/nav-badges")
    @RequiresMerchant(VIEW)
    Map<String, String> badges(@PathVariable String merchantId, CurrentMember member, Locale locale) {
        return navBadges.of(new NavBadgeContributor.Context(merchantId, member.userId(), member.role(), locale));
    }
}
