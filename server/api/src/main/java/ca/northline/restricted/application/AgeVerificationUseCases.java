package ca.northline.restricted.application;

import ca.northline.restricted.api.AgeVerifications.Status;
import org.jspecify.annotations.Nullable;

/** The customer's side of the age check ({@code /api/v1/me/age-verification}). */
public final class AgeVerificationUseCases {
    private AgeVerificationUseCases() {}

    public interface StartAgeCheck {
        /**
         * Opens a session with the identity provider (a new one each time: a hosted-flow URL is single use).
         *
         * @param returnTo {@code web | app}: where the provider sends the customer back
         */
        Started start(String userId, String returnTo);
    }

    public interface ReadAgeCheck {
        /** The customer's status; a pending session is read from the provider (a webhook may be late). */
        Status read(String userId);
    }

    public interface ApplyAgeSession {
        /** A provider update (webhook or the local fake): applies it to the pending check that holds the session. */
        void apply(String sessionId, @Nullable String status, @Nullable String lastError);
    }

    /** @param url the provider's hosted flow, single use — open it at once */
    public record Started(String url, Status status) {}
}
