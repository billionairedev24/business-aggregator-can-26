package ca.northline.catalogue.application;

import static ca.northline.catalogue.domain.ListingMessages.IMAGE_REQUIRED;
import static ca.northline.catalogue.domain.ListingMessages.IMAGE_TOO_LARGE;
import static ca.northline.catalogue.domain.ListingMessages.IMAGE_TOO_SMALL;
import static ca.northline.catalogue.domain.ListingMessages.IMAGE_TYPE;

import ca.northline.catalogue.domain.ListingMessages;
import ca.northline.catalogue.domain.MediaAsset;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.storage.ObjectKeys;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Image uploads: JPG/PNG only, ≥ 1000 px on the longest side, ≤ 15 MB (the image standards; the editor checks the
 * same client-side). The perceptual hash and the white-background measure feed automated vetting.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class MediaService implements ManageMedia {

    private final MediaRepository media;
    private final MediaStorage storage;
    private final ImageInspector inspector;

    @Override
    @Transactional
    public MediaAsset upload(String merchantId, byte[] bytes) {
        if (bytes.length == 0) {
            throw RuleViolation.of("file", "required", IMAGE_REQUIRED);
        }
        if (bytes.length > ListingMessages.IMAGE_MAX_BYTES) {
            throw RuleViolation.of("file", "size", IMAGE_TOO_LARGE);
        }
        var facts = inspector.inspect(bytes).orElseThrow(() -> RuleViolation.of("file", "format", IMAGE_TYPE));
        var id = Ids.next();
        var asset = new MediaAsset(
                id,
                merchantId,
                ObjectKeys.merchantObject(merchantId, id, facts.contentType()),
                facts.contentType(),
                facts.width(),
                facts.height(),
                bytes.length,
                facts.onWhite(),
                facts.phash());
        if (!asset.largeEnough()) {
            throw RuleViolation.of("file", "dimensions", IMAGE_TOO_SMALL);
        }
        storage.put(asset.storageKey(), bytes, asset.contentType());
        media.insert(asset);
        return asset;
    }

    @Override
    public Optional<Content> content(String mediaId) {
        return media.find(mediaId)
                .flatMap(m -> storage.get(m.storageKey()).map(bytes -> new Content(bytes, m.contentType())));
    }
}
