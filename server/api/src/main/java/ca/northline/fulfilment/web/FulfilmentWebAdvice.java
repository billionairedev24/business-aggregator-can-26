package ca.northline.fulfilment.web;

import ca.northline.fulfilment.application.NotACourier;
import ca.northline.fulfilment.application.TooManyPings;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The courier app's 403 when the signed-in person isn't an active courier ({@code not_a_courier}) and its 429 when the
 * app sends positions too often ({@code too_many_pings}, {@code Retry-After} in seconds).
 */
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

    @ExceptionHandler
    ResponseEntity<ProblemDetail> tooManyPings(TooManyPings ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setType(URI.create("https://northline.ca/problems/too-many-pings"));
        problem.setProperty("code", "too_many_pings");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(Math.max(1, (ex.retryAfterMs() + 999) / 1000)))
                .body(problem);
    }
}
