package ca.northline.fulfilment.api;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The photo a courier took at the door as an order's proof of delivery, for the customer's delivered screen (web and
 * app): a short-lived signed URL through the storage port, never the bytes or a lasting link. The caller has checked the
 * order is the customer's. Empty when there is none: a PIN or signature drop-off, retention removed it (S-107), or the
 * delivery needed an ID check at the door — no photo of such a handoff is ever shown (it could show the ID).
 */
public interface DeliveryProofPhotos {

    /** How long a link works: long enough to load the image, short enough not to be worth sharing. */
    Duration LINK_TTL = Duration.ofMinutes(5);

    Optional<ProofPhoto> photo(String orderId);

    record ProofPhoto(URI url, Instant expiresAt) {}
}
