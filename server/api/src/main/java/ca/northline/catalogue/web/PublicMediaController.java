package ca.northline.catalogue.web;

import ca.northline.catalogue.application.ManageMedia;
import ca.northline.shared.NotFound;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listing images for the storefront and consumer app ({@code /api/v1/public/**} is open in SecurityConfig). Only
 * approved images are served; anything else is a 404, so a key can't be probed (S-123).
 */
@RestController
@RequiredArgsConstructor
class PublicMediaController {

    private final ManageMedia manageMedia;

    @GetMapping("/api/v1/public/catalogue/media/{mediaId}")
    ResponseEntity<byte[]> content(@PathVariable String mediaId) {
        var content = manageMedia.publicContent(mediaId).orElseThrow(() -> new NotFound("media", mediaId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes().toArray());
    }
}
