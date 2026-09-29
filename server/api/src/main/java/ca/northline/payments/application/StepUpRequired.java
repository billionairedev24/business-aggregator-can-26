package ca.northline.payments.application;

/** 403 {@code code=step_up_required}: confirm with a passkey (or authenticator code) and retry with the new proof. */
public final class StepUpRequired extends RuntimeException {
    public StepUpRequired(String message) {
        super(message);
    }
}
