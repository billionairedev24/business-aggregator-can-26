package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.MediaAsset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Outbound port for {@code catalogue.media} rows. */
public interface MediaRepository {

    void insert(MediaAsset asset);

    Optional<MediaAsset> find(String id);

    /** In the order of {@code ids}; unknown ids are skipped. */
    List<MediaAsset> findAll(Collection<String> ids);

    /** Is there an image by another merchant whose perceptual hash is within {@code maxDistance} bits? */
    boolean hasNearDuplicate(long phash, String exceptMerchantId, int maxDistance);

    /** Records which listing / catalogue record uses the images. */
    void attach(Collection<String> ids, String ownerType, String ownerId);
}
