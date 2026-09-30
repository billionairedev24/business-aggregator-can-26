package ca.northline.orders.web;

import ca.northline.payments.api.MoneyRequests;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 403 {@code step_up_required} for the food checkout, in the shape payouts use (the web app asks to confirm). */
@RestControllerAdvice(basePackageClasses = FoodOrderWebAdvice.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class FoodOrderWebAdvice {

    @ExceptionHandler
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ProblemDetail stepUp(MoneyRequests.StepUpNeeded ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, String.valueOf(ex.getMessage()));
        problem.setType(URI.create("https://northline.ca/problems/step-up-required"));
        problem.setProperty("code", "step_up_required");
        return problem;
    }
}
