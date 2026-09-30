package ca.northline.payments.api;

import org.jspecify.annotations.Nullable;

/**
 * The step-up proof check behind payouts (Finance) for other modules: consumer checkout (S-51) asks a person signed in
 * without a second factor for a fresh one. {@code proof} is northline-auth's step-up JWT ({@code X-Step-Up}); it is
 * consumed on first use.
 */
public interface PaymentStepUp {

    /** True when {@code proof} is a fresh step-up of {@code userId} (and marks it used). */
    boolean verified(String userId, @Nullable String proof);
}
