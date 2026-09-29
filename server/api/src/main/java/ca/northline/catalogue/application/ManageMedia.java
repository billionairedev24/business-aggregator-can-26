package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.MediaAsset;
import java.util.Optional;

/** Upload listing images (validated against the image standards) and read them back. */
public interface ManageMedia {

    record Content(byte[] bytes, String contentType) {}

    MediaAsset upload(String merchantId, byte[] bytes);

    Optional<Content> content(String mediaId);
}
