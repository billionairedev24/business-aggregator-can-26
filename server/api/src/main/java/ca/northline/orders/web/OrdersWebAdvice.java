package ca.northline.orders.web;

import ca.northline.orders.application.StepUpNeeded;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Checkout's 403s in the API's ProblemDetail shape: {@code step_up_required} / {@code second_factor_required}. */
@RestControllerAdvice(basePackageClasses = OrdersWebAdvice.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class OrdersWebAdvice {

    @ExceptionHandler
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ProblemDetail stepUp(StepUpNeeded ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setType(URI.create("https://northline.ca/problems/" + ex.code().replace('_', '-')));
        problem.setProperty("code", ex.code());
        return problem;
    }
}
