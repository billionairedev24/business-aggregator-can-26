package ca.northline.region.web;

import ca.northline.region.application.PlacesAutocomplete;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 503 {@code places_unavailable} and 429 {@code rate_limited} in the API's ProblemDetail shape. */
@Slf4j
@RestControllerAdvice(basePackageClasses = GeoWebAdvice.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class GeoWebAdvice {

    @ExceptionHandler
    ResponseEntity<ProblemDetail> unavailable(PlacesAutocomplete.Unavailable ex) {
        log.warn("Address lookup failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "places_unavailable",
                        "We couldn't look up addresses right now. Try again in a moment."));
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> tooMany(GeoController.TooManyLookups ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "60")
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", GeoController.TooManyLookups.MESSAGE));
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://northline.ca/problems/" + code.replace('_', '-')));
        problem.setProperty("code", code);
        return problem;
    }
}
