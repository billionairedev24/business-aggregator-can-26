package ca.northline.hire.web;

import ca.northline.hire.application.SecondFactorRequired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 403 {@code step_up_required} in the API's ProblemDetail shape (same code as the Studio's payouts). */
@RestControllerAdvice(basePackageClasses = HireWebAdvice.class)
class HireWebAdvice {

    @ExceptionHandler
    ProblemDetail stepUp(SecondFactorRequired e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        problem.setProperty("code", "step_up_required");
        return problem;
    }
}
