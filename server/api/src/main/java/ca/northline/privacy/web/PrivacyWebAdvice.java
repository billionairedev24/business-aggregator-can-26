package ca.northline.privacy.web;

import ca.northline.platform.MessageCatalogue;
import ca.northline.privacy.application.IdentityCheckRequired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/** 403 {@code step_up_required} in the API's ProblemDetail shape (as S-51 checkout), in the caller's language. */
@RestControllerAdvice(basePackageClasses = PrivacyWebAdvice.class)
class PrivacyWebAdvice {

    private static final MessageCatalogue FRENCH = MessageCatalogue.frenchCanadian();

    @ExceptionHandler
    ProblemDetail stepUp(IdentityCheckRequired e, WebRequest request) {
        var detail = FRENCH.localize(
                IdentityCheckRequired.MESSAGE,
                request.getHeader(HttpHeaders.ACCEPT_LANGUAGE),
                MessageCatalogue.Arguments.AS_IS);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail);
        problem.setProperty("code", IdentityCheckRequired.CODE);
        return problem;
    }
}
