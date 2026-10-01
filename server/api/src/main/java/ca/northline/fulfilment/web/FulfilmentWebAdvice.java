package ca.northline.fulfilment.web;

import ca.northline.fulfilment.application.NotACourier;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The courier app's 403 when the signed-in person isn't an active courier: {@code not_a_courier}. */
@RestControllerAdvice(basePackageClasses = FulfilmentWebAdvice.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class FulfilmentWebAdvice {

    @ExceptionHandler
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ProblemDetail notACourier(NotACourier ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setType(URI.create("https://northline.ca/problems/not-a-courier"));
        problem.setProperty("code", "not_a_courier");
        return problem;
    }
}
