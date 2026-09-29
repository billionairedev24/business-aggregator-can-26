package ca.northline.merchants.application;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Completes one check of the Verification step: runs the external lookup, stores the entered number, attaches an
 * uploaded document, records a signature or a choice, or books the kitchen visit.
 *
 * <p>{@code choice}: {@code signed} (attestations) · {@code standard | perishables} (returns policy) · {@code none}
 * (product-category permits) · {@code not_applicable} (AGLC).
 */
public interface CompleteVerification {

    record Command(
            String merchantId,
            String verificationId,
            @Nullable String reference,
            @Nullable String documentId,
            @Nullable LocalDate expiresOn,
            @Nullable String choice) {}

    OnboardingView complete(Command command);
}
