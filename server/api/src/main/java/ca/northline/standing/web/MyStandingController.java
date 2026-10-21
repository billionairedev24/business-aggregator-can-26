package ca.northline.standing.web;

import ca.northline.shared.security.CurrentUser;
import ca.northline.standing.application.StandingUseCases.CustomerStanding;
import ca.northline.standing.application.StandingUseCases.ViewStanding;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-156, the customer's account: consequences of their own no-shows (what providers see, and that they confirm the
 * customer's bookings first), with the reason. Single-factor sessions are enough, like the rest of {@code /me}.
 *
 * <pre>
 * GET /api/v1/me/standing   {active: [Consequence], past: [Consequence]}
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class MyStandingController {

    private final ViewStanding view;

    @GetMapping("/api/v1/me/standing")
    CustomerStanding standing(CurrentUser user) {
        return view.customer(user.userId());
    }
}
