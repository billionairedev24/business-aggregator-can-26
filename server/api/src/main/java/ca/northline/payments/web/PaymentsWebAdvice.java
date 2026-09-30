package ca.northline.payments.web;

import ca.northline.payments.application.StepUpRequired;
import ca.northline.payments.application.StripeEventVerifier;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 403 {@code step_up_required} and the Stripe webhook errors in the API's ProblemDetail shape (the shared advice handles
 * everything else).
 */
@RestControllerAdvice(basePackageClasses = PaymentsWebAdvice.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PaymentsWebAdvice {

    @ExceptionHandler
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ProblemDetail stepUp(StepUpRequired ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setType(URI.create("https://northline.ca/problems/step-up-required"));
        problem.setProperty("code", "step_up_required");
        return problem;
    }

    /** A webhook whose Stripe-Signature doesn't hold (or is too old): 400, Stripe shows it as failed. */
    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail badSignature(StripeEventVerifier.InvalidSignature ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_signature", "The Stripe signature doesn't match.");
    }

    @ExceptionHandler
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ProblemDetail webhooksOff(StripeEventVerifier.NotConfigured ex) {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE, "webhooks_unconfigured", "Stripe webhooks aren't configured here.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://northline.ca/problems/" + code.replace('_', '-')));
        problem.setProperty("code", code);
        return problem;
    }
}
