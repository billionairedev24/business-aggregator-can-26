package ca.northline.fulfilment.application;

import ca.northline.fulfilment.api.DeliveryProofPhotos;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link DeliveryProofPhotos}: the done drop-off's photo, if one is kept, as a {@link ProofStorage#signedUrl}. */
@Service
@RequiredArgsConstructor
class ProofPhotoService implements DeliveryProofPhotos {

    private final RunStore runs;
    private final ProofStorage storage;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Optional<ProofPhoto> photo(String orderId) {
        return runs.proofPhotoKey(orderId)
                .map(key -> new ProofPhoto(
                        storage.signedUrl(key, LINK_TTL), clock.instant().plus(LINK_TTL)));
    }
}
