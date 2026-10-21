package ca.northline.payments.api;

import java.time.Duration;

/**
 * S-156: how much later a job's escrow is released when it was completed without a photo (S-93 rule
 * {@code missing_photo_delay}, default 48 h; zero = no delay). Declared here and implemented by trust, which owns the
 * rules (trust depends on payments, not the other way round).
 */
public interface CompletionPhotoRule {

    Duration missingPhotoDelay();
}
