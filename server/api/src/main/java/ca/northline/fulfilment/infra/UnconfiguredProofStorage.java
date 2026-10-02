package ca.northline.fulfilment.infra;

import ca.northline.fulfilment.application.ProofStorage;
import ca.northline.shared.storage.UsesLocalStorage;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Outside local/test while {@code STORAGE_PROVIDER=local}: proof uploads fail instead of silently vanishing. */
@Component
@Profile("!local & !test")
@UsesLocalStorage
class UnconfiguredProofStorage implements ProofStorage {

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        throw new IllegalStateException("Proof-of-delivery storage is not configured (set STORAGE_PROVIDER, S-10).");
    }

    @Override
    public Optional<StoredFile> get(String key) {
        return Optional.empty();
    }

    @Override
    public void delete(String key) {
        // nothing was ever stored
    }
}
