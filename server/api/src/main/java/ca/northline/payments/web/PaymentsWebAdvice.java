package ca.northline.payments.web;

import ca.northline.payments.application.StepUpRequired;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 403 {@code step_up_required} in the API's ProblemDetail shape (the shared advice handles everything else). */
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
}
