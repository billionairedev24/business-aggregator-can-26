package ca.northline.fulfilment.web;

import ca.northline.fulfilment.application.ProofStorage;
import ca.northline.fulfilment.infra.LocalProofLinks;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code local}/{@code test} only: what object storage's presigned GET does in the cloud, for the proof-of-delivery
 * photo links of {@link LocalProofLinks} — {@code GET /api/v1/dev/proof-photos/{token}}, no token needed (the signed
 * link is the authorisation), 403 when the link is forged or expired.
 */
@RestController
@RequestMapping("/api/v1/dev/proof-photos")
@Profile({"local", "test"})
@RequiredArgsConstructor
class DevProofPhotoController {

    static final String LINK_INVALID = "This link has expired. Open the order again.";

    private final LocalProofLinks links;
    private final ProofStorage proofs;

    @GetMapping("/{token}")
    ResponseEntity<byte[]> photo(@PathVariable String token) {
        var file = links.verify(token).flatMap(proofs::get).orElseThrow(() -> new AccessDeniedException(LINK_INVALID));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes().toArray());
    }
}
