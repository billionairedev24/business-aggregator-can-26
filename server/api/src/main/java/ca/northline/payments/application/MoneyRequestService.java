package ca.northline.payments.application;

import ca.northline.payments.api.MoneyRequests;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** {@link MoneyRequests} over the payments module's {@link IdempotencyStore} and {@link StepUpVerifier} (S-57). */
@Service
@RequiredArgsConstructor
class MoneyRequestService implements MoneyRequests {

    private final IdempotencyStore store;
    private final StepUpVerifier stepUp;

    @Override
    public Optional<Stored> claim(String scope, String key, String fingerprint) {
        return store.claim(scope, key, fingerprint, IdempotencyStore.TTL)
                .map(s -> new Stored(s.fingerprint(), s.status(), s.body()));
    }

    @Override
    public void complete(String scope, String key, int status, String body) {
        store.complete(scope, key, status, body);
    }

    @Override
    public void release(String scope, String key) {
        store.release(scope, key);
    }

    @Override
    public void requireStepUp(String userId, @Nullable String proof) {
        try {
            stepUp.verify(userId, proof);
        } catch (StepUpRequired e) {
            throw new StepUpNeeded("Confirm it's you to pay: use your passkey or authenticator app.");
        }
    }
}
