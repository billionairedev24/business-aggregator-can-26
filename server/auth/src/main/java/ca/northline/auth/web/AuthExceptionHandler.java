package ca.northline.auth.web;

import ca.northline.auth.application.FlowRejected;
import ca.northline.auth.application.InvalidInput;
import ca.northline.auth.application.InvalidInput.Violation;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Same error format as the api: 422 {@code {"errors":[{field, rule, message}]}} (one per field, most basic rule
 * first) and RFC 9457 ProblemDetail with a {@code code} for everything else.
 */
@RestControllerAdvice(basePackages = "ca.northline.auth.web")
class AuthExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Map<String, String> RULES =
            Map.of("NotNull", "required", "NotBlank", "required", "AssertTrue", "required", "Pattern", "format");
    private static final List<String> PRIORITY = List.of("required", "format");

    /** The 422 body. */
    record Errors(List<Violation> errors) {}

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var violations = new ArrayList<Violation>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(fe -> violations.add(new Violation(
                        fe.getField(),
                        RULES.getOrDefault(Objects.requireNonNullElse(fe.getCode(), ""), "invalid"),
                        Objects.requireNonNullElse(fe.getDefaultMessage(), "Check this field."))));
        return unprocessable(violations);
    }

    @ExceptionHandler
    ResponseEntity<Object> invalid(InvalidInput ex) {
        return unprocessable(ex.getViolations());
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> rejected(FlowRejected ex) {
        var status = switch (ex.getReason()) {
            case NOT_STARTED -> HttpStatus.CONFLICT;
            case THROTTLED, LOCKED, RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case STEP_UP_REQUIRED -> HttpStatus.FORBIDDEN;
            case LAST_FACTOR, CURRENT_SESSION -> HttpStatus.CONFLICT;
            case GONE -> HttpStatus.NOT_FOUND;
            case CODE_NOT_SENT, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var problem = problem(status, ex.getReason().code(), ex.getMessage());
        var response = ResponseEntity.status(status);
        var retry = ex.getRetryAfterSeconds();
        if (retry != null) {
            problem.setProperty("retryAfterSeconds", retry);
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(retry));
        }
        return response.body(problem);
    }

    static ProblemDetail problem(HttpStatus status, String code, @Nullable String detail) {
        var problem =
                ProblemDetail.forStatusAndDetail(status, Objects.requireNonNullElse(detail, status.getReasonPhrase()));
        problem.setType(URI.create("https://northline.ca/problems/" + code.replace('_', '-')));
        problem.setProperty("code", code);
        return problem;
    }

    private static ResponseEntity<Object> unprocessable(List<Violation> violations) {
        var byField = new LinkedHashMap<String, Violation>();
        violations.stream()
                .sorted(Comparator.comparingInt(AuthExceptionHandler::rank))
                .forEach(v -> byField.putIfAbsent(v.field(), v));
        var errors = byField.values().stream()
                .sorted(Comparator.comparing(Violation::field))
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new Errors(errors));
    }

    private static int rank(Violation v) {
        int i = PRIORITY.indexOf(v.rule());
        return i < 0 ? PRIORITY.size() : i;
    }
}
