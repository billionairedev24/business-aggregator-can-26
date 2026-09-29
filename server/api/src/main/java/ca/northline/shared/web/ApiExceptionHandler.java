package ca.northline.shared.web;

import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import ca.northline.shared.security.MerchantAccessDenied;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One error format for the whole API.
 *
 * <ul>
 *   <li>422 {@code {"errors":[{field, rule, message}]}} — Bean Validation failures and {@link RuleViolation}s.
 *   <li>404 / 403 / 409 (and Spring MVC's own 4xx) — RFC 9457 {@link ProblemDetail}; 403 and 409 carry a {@code code}.
 * </ul>
 */
@Slf4j
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final HttpStatus UNPROCESSABLE = HttpStatus.UNPROCESSABLE_CONTENT;
    private static final String PROBLEM_BASE = "https://northline.ca/problems/";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var violations = new ArrayList<Violation>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> violations.add(fromFieldError(fe)));
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(ge -> violations.add(violation(ge.getObjectName(), ge.getCode(), ge.getDefaultMessage())));
        return unprocessable(violations);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var violations = new ArrayList<Violation>();
        for (var result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors errors) {
                errors.getFieldErrors().forEach(fe -> violations.add(fromFieldError(fe)));
                continue;
            }
            var param = Objects.requireNonNullElse(result.getMethodParameter().getParameterName(), "param");
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                violations.add(violation(param, lastCode(error.getCodes()), error.getDefaultMessage()));
            }
        }
        return unprocessable(violations);
    }

    @ExceptionHandler
    ResponseEntity<Object> ruleViolation(RuleViolation ex) {
        return unprocessable(ex.getViolations());
    }

    @ExceptionHandler
    ResponseEntity<Object> constraintViolation(ConstraintViolationException ex) {
        return unprocessable(ex.getConstraintViolations().stream()
                .map(ApiExceptionHandler::fromConstraintViolation)
                .toList());
    }

    @ExceptionHandler
    ProblemDetail notFound(NotFound ex) {
        return problem(HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail conflict(Conflict ex) {
        return problem(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail staleWrite(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "stale", "Someone else changed this. Reload and try again.");
    }

    @ExceptionHandler
    ProblemDetail duplicate(DuplicateKeyException ex) {
        return problem(HttpStatus.CONFLICT, "duplicate", "That already exists.");
    }

    @ExceptionHandler
    ProblemDetail integrity(DataIntegrityViolationException ex) {
        // DB CHECK/trigger (V016) caught something Bean Validation/domain rules should have: log loudly, answer 409.
        log.warn(
                "Database constraint rejected a write: {}",
                ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "constraint_violation", "This change breaks a business rule.");
    }

    @ExceptionHandler
    ProblemDetail forbidden(AccessDeniedException ex) {
        var code = ex instanceof MerchantAccessDenied denied ? denied.reason().code() : "forbidden";
        return problem(HttpStatus.FORBIDDEN, code, Objects.requireNonNullElse(ex.getMessage(), "Forbidden"));
    }

    static ProblemDetail problem(HttpStatus status, String code, @Nullable String detail) {
        var problem =
                ProblemDetail.forStatusAndDetail(status, Objects.requireNonNullElse(detail, status.getReasonPhrase()));
        problem.setType(URI.create(PROBLEM_BASE + code.replace('_', '-')));
        problem.setProperty("code", code);
        return problem;
    }

    private static ResponseEntity<Object> unprocessable(List<Violation> violations) {
        return ResponseEntity.status(UNPROCESSABLE).body(ValidationErrors.of(violations));
    }

    private static Violation fromFieldError(FieldError fe) {
        return violation(fe.getField(), fe.getCode(), fe.getDefaultMessage());
    }

    private static Violation fromConstraintViolation(ConstraintViolation<?> cv) {
        String field = "value";
        for (Path.Node node : cv.getPropertyPath()) {
            if (node.getName() != null) {
                field = node.getName();
            }
        }
        var constraint =
                cv.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        return violation(field, constraint, cv.getMessage());
    }

    private static Violation violation(String field, @Nullable String constraint, @Nullable String message) {
        var rule = ValidationErrors.ruleFor(Objects.requireNonNullElse(constraint, "invalid"));
        return new Violation(field, rule, Objects.requireNonNullElse(message, "Check this field."));
    }

    private static @Nullable String lastCode(String @Nullable [] codes) {
        return codes == null || codes.length == 0 ? null : codes[codes.length - 1];
    }
}
