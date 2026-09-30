package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.MediaAsset;
import java.util.Optional;

/**
 * Upload listing images (validated against the image standards) and read them back. Unvetted images stay private to
 * the business that uploaded them (S-123).
 */
public interface ManageMedia {

    record Content(byte[] bytes, String contentType) {}

    MediaAsset upload(String merchantId, byte[] bytes);

    /**
     * The image as shown in {@code merchantId}'s Studio: its own uploads, or another business's approved images (a
     * shared catalogue record). Empty when unknown.
     *
     * @throws org.springframework.security.access.AccessDeniedException another business's image that isn't approved
     */
    Optional<Content> content(String merchantId, String mediaId);

    /** Approved images only (storefront, consumer app); empty for anything else, so ids can't be probed. */
    Optional<Content> publicContent(String mediaId);
}
