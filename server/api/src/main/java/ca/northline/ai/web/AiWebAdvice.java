package ca.northline.ai.web;

import ca.northline.ai.api.AiRateLimited;
import ca.northline.ai.api.AiUnavailable;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The AI errors in the API's ProblemDetail shape, wherever an AI feature is called from (every module's controllers):
 * 503 {@code ai_unavailable} and 429 {@code ai_rate_limited} with {@code Retry-After} and the {@code limit} that was hit.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AiWebAdvice {

    @ExceptionHandler
    ResponseEntity<ProblemDetail> unavailable(AiUnavailable ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, AiUnavailable.CODE, String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> rateLimited(AiRateLimited ex) {
        var problem = problem(HttpStatus.TOO_MANY_REQUESTS, AiRateLimited.CODE, String.valueOf(ex.getMessage()));
        problem.setProperty("limit", ex.getLimit());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(
                        "Retry-After",
                        Long.toString(Math.max(1, ex.getRetryAfter().toSeconds())))
                .body(problem);
    }

    static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://northline.ca/problems/" + code.replace('_', '-')));
        problem.setProperty("code", code);
        return problem;
    }
}
