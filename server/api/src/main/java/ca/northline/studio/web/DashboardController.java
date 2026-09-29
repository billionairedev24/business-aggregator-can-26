package ca.northline.studio.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import ca.northline.studio.application.Dashboard;
import ca.northline.studio.application.ViewDashboard;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/merchants/{merchantId}/dashboard}. The {@link Dashboard} read model is purpose-built for this
 * screen and serialized as is (docs/DECISIONS.md "Operations").
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/dashboard")
@RequiredArgsConstructor
class DashboardController {

    private final ViewDashboard viewDashboard;

    @GetMapping
    @RequiresMerchant(VIEW)
    Dashboard dashboard(@PathVariable String merchantId, CurrentMember member, Locale locale) {
        return viewDashboard.view(merchantId, member.userId(), locale);
    }
}
