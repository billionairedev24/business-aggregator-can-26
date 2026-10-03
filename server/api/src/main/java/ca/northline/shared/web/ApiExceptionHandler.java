package ca.northline.shared.web;

import ca.northline.platform.MessageCatalogue;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.PlaceNames;
import ca.northline.shared.ProviderUnavailable;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import ca.northline.shared.security.MerchantAccessDenied;
import ca.northline.shared.security.StaffAccessDenied;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessResourceFailureException;
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
 *
 * <p>S-40: messages are English in the code (validation-rules.md's exact text). When the request's
 * {@code Accept-Language} prefers French, the 422 messages and the 403/409 details go out in the fr-CA wording of
 * {@code docs/spec/validation-messages.fr-CA.tsv}; place names in them come from the region module ({@link PlaceNames}).
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final HttpStatus UNPROCESSABLE = HttpStatus.UNPROCESSABLE_CONTENT;
    private static final String PROBLEM_BASE = "https://northline.ca/problems/";
    private static final MessageCatalogue FRENCH = MessageCatalogue.frenchCanadian();
    static final String OVERLOADED = "Northline is very busy right now. Try again in a moment.";
    static final int OVERLOADED_RETRY_AFTER_S = 2;

    private final ObjectProvider<PlaceNames> placeNames;

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var violations = new ArrayList<Violation>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> violations.add(fromFieldError(fe)));
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(ge -> violations.add(violation(ge.getObjectName(), ge.getCode(), ge.getDefaultMessage())));
        return unprocessable(violations, request);
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
        return unprocessable(violations, request);
    }

    @ExceptionHandler
    ResponseEntity<Object> ruleViolation(RuleViolation ex, WebRequest request) {
        return unprocessable(ex.getViolations(), request);
    }

    @ExceptionHandler
    ResponseEntity<Object> constraintViolation(ConstraintViolationException ex, WebRequest request) {
        return unprocessable(
                ex.getConstraintViolations().stream()
                        .map(ApiExceptionHandler::fromConstraintViolation)
                        .toList(),
                request);
    }

    @ExceptionHandler
    ProblemDetail notFound(NotFound ex) {
        return problem(HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail conflict(Conflict ex, WebRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                ex.getCode(),
                localize(Objects.requireNonNullElse(ex.getMessage(), HttpStatus.CONFLICT.getReasonPhrase()), request));
    }

    /** S-115: a provider outage (Stripe during checkout) — 503, nothing done, try again later. */
    @ExceptionHandler
    ResponseEntity<ProblemDetail> providerUnavailable(ProviderUnavailable ex, WebRequest request) {
        log.warn("Provider unavailable ({}): {}", ex.getCode(), String.valueOf(ex.getCause()));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ex.getCode(),
                        localize(Objects.requireNonNullElse(ex.getMessage(), "Unavailable"), request)));
    }

    /**
     * S-119: no database connection — every one stayed busy for {@code DB_CONNECTION_TIMEOUT_MS} (the instance is over
     * its capacity; Spring's {@code CannotGetJdbcConnectionException}) or the database is unreachable. Shed the request
     * (503, try again in a moment) rather than queue it: under the first local stress test the waiting requests filled
     * the heap and the api died of OutOfMemoryError instead of answering.
     */
    @ExceptionHandler
    ResponseEntity<ProblemDetail> overloaded(DataAccessResourceFailureException ex, WebRequest request) {
        log.warn("Overloaded, request shed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(OVERLOADED_RETRY_AFTER_S))
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "overloaded", localize(OVERLOADED, request)));
    }

    @ExceptionHandler
    ProblemDetail staleWrite(OptimisticLockingFailureException ex, WebRequest request) {
        return problem(
                HttpStatus.CONFLICT, "stale", localize("Someone else changed this. Reload and try again.", request));
    }

    @ExceptionHandler
    ProblemDetail duplicate(DuplicateKeyException ex, WebRequest request) {
        return problem(HttpStatus.CONFLICT, "duplicate", localize("That already exists.", request));
    }

    @ExceptionHandler
    ProblemDetail integrity(DataIntegrityViolationException ex, WebRequest request) {
        // DB CHECK/trigger (V016) caught something Bean Validation/domain rules should have: log loudly, answer 409.
        log.warn(
                "Database constraint rejected a write: {}",
                ex.getMostSpecificCause().getMessage());
        return problem(
                HttpStatus.CONFLICT, "constraint_violation", localize("This change breaks a business rule.", request));
    }

    @ExceptionHandler
    ProblemDetail forbidden(AccessDeniedException ex, WebRequest request) {
        var code = switch (ex) {
            case MerchantAccessDenied denied -> denied.reason().code();
            case StaffAccessDenied denied -> denied.reason().code();
            default -> "forbidden";
        };
        return problem(
                HttpStatus.FORBIDDEN,
                code,
                localize(Objects.requireNonNullElse(ex.getMessage(), "Forbidden"), request));
    }

    static ProblemDetail problem(HttpStatus status, String code, @Nullable String detail) {
        var problem =
                ProblemDetail.forStatusAndDetail(status, Objects.requireNonNullElse(detail, status.getReasonPhrase()));
        problem.setType(URI.create(PROBLEM_BASE + code.replace('_', '-')));
        problem.setProperty("code", code);
        return problem;
    }

    private ResponseEntity<Object> unprocessable(List<Violation> violations, WebRequest request) {
        var localized = violations.stream()
                .map(v -> new Violation(v.field(), v.rule(), localize(v.message(), request)))
                .toList();
        return ResponseEntity.status(UNPROCESSABLE).body(ValidationErrors.of(localized));
    }

    /** The message in the caller's language (S-40): English unless {@code Accept-Language} prefers French. */
    private String localize(String english, WebRequest request) {
        return FRENCH.localize(english, request.getHeader(HttpHeaders.ACCEPT_LANGUAGE), this::frenchArgument);
    }

    /** A place captured from an English message, in French ("in": with its preposition); other parts as they are. */
    private String frenchArgument(String value, @Nullable String form) {
        var in = "in".equals(form);
        var names = placeNames.getIfAvailable();
        var french = names == null ? Optional.<String>empty() : names.french(value, in);
        return french.orElse(in ? "à " + value : value);
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
