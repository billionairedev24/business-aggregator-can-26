package ca.northline.config.web;

import ca.northline.config.DevOutbox;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LOCAL PROFILE ONLY (S-117) — the api's email and SMS outbox ({@link DevOutbox}) for the end-to-end suite:
 *
 * <pre>
 * GET /api/v1/dev/outbox?to=owner@example.com    {items: [{kind, to, subject, text, tag, at}]}, newest first
 * GET /api/v1/dev/outbox?to=+15875550101         texts and calls to that number
 * </pre>
 *
 * No sign-in (the suite reads it before anyone has signed in, like the email previews). 404 under every other profile.
 */
@Profile("local")
@RestController
@RequiredArgsConstructor
public class DevOutboxController { // public: DevOnlyRoutesTest registers it under each profile

    private final DevOutbox outbox;

    record Items(List<DevOutbox.Message> items) {}

    @GetMapping("/api/v1/dev/outbox")
    ResponseEntity<Items> outbox(@RequestParam String to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Items(outbox.to(to)));
    }
}
