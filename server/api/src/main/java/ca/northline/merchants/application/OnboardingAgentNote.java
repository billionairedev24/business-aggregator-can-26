package ca.northline.merchants.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-156 (S-79's open item): the agent's latest "Request info" on the application — the checks to redo and the note —
 * for the onboarding wizard (it was only in the email). Empty when the latest decision isn't a request for info or the
 * business was approved since.
 */
public interface OnboardingAgentNote {

    Optional<Note> latest(String merchantId);

    record Note(List<String> checkKeys, @Nullable String note, Instant decidedAt) {
        public Note {
            checkKeys = List.copyOf(checkKeys);
        }
    }
}
