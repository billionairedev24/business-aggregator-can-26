package ca.northline.payments.infra;

import ca.northline.payments.application.DisputeEvidenceStorage;
import ca.northline.shared.storage.UsesLocalStorage;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Outside local/test while {@code STORAGE_PROVIDER=local}: uploads fail instead of silently vanishing. */
@Component
@Profile("!local & !test")
@UsesLocalStorage
class UnconfiguredDisputeEvidenceStorage implements DisputeEvidenceStorage {

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        throw new IllegalStateException("Dispute evidence storage is not configured (set STORAGE_PROVIDER, S-10).");
    }

    @Override
    public Optional<StoredFile> get(String key) {
        return Optional.empty();
    }
}
