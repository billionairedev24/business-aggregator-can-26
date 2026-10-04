package ca.northline.auth.sms;

import ca.northline.auth.sms.DevOutbox.Sent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LOCAL PROFILE ONLY (S-117) — the local SMS fake's outbox for the end-to-end suite:
 *
 * <pre>
 * GET /api/auth/dev/outbox?to=+15875550101   {items: [{to, channel, code, at}]}, newest first
 * </pre>
 *
 * The route doesn't exist under any other profile (404); DevOnlyBeansTest checks it under {@code prod}.
 */
@Profile("local")
@RestController
@RequiredArgsConstructor
class DevOutboxController {

    private final DevOutbox outbox;

    record Items(List<Sent> items) {}

    @GetMapping("/api/auth/dev/outbox")
    ResponseEntity<Items> outbox(@RequestParam String to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Items(outbox.to(to)));
    }
}
