package ca.northline.merchants.application;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link OnboardingAgentNote} over S-79's {@code application_decisions}, newest first. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class OnboardingAgentNoteService implements OnboardingAgentNote {

    private final VerificationQueueStore store;

    @Override
    public Optional<Note> latest(String merchantId) {
        return store.decisions(merchantId).stream()
                .findFirst()
                .filter(d -> d.decision() == VerificationQueue.Decision.INFO_REQUESTED)
                .map(d -> new Note(d.checkKeys(), d.note(), d.decidedAt()));
    }
}
