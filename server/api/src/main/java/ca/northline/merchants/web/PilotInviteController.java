package ca.northline.merchants.web;

import ca.northline.merchants.application.PilotInviteLinks;
import ca.northline.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-120: the Studio's view of a pilot invite link — {@code GET /api/v1/pilot-invites/{token}} → business type, working
 * name, market (id, city, province), expiry, state. Any signed-in person may read it (they hold the link); accepting
 * it is the Account step's {@code POST /api/v1/merchants} with {@code pilotInvite}.
 */
@RestController
@RequiredArgsConstructor
class PilotInviteController {

    private final PilotInviteLinks links;

    @GetMapping("/api/v1/pilot-invites/{token}")
    PilotInviteLinks.Preview preview(@PathVariable String token, CurrentUser user) {
        return links.preview(token);
    }
}
