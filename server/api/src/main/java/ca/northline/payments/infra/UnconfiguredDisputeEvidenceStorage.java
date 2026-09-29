package ca.northline.payments.infra;

import ca.northline.payments.application.DisputeEvidenceStorage;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Outside local/test: no object storage chosen yet (DECISIONS.md). Uploads fail instead of silently vanishing. */
@Component
@Profile("!local & !test")
class UnconfiguredDisputeEvidenceStorage implements DisputeEvidenceStorage {

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        throw new IllegalStateException("Dispute evidence storage is not configured (object storage in ca-central-1).");
    }

    @Override
    public Optional<StoredFile> get(String key) {
        return Optional.empty();
    }
}
