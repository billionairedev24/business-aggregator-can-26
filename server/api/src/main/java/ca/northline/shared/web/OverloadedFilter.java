package ca.northline.shared.web;

import ca.northline.platform.MessageCatalogue;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Engineering follow-ups (S-119 finding F7): a servlet filter that needs the database (dev auth, a token's membership
 * lookup) and finds no free connection threw past Spring MVC's exception handling; the container's error dispatch then
 * met Spring Security without an authentication and answered <strong>403</strong>, with a stack trace per request. This
 * filter runs ahead of the security chain and answers such a request the way {@link ApiExceptionHandler} answers one
 * from a controller: 503 {@code overloaded}, {@code Retry-After}, in the caller's language. Anything else passes through
 * unchanged.
 */
@Slf4j
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1)
@RequiredArgsConstructor
class OverloadedFilter extends OncePerRequestFilter {

    private static final MessageCatalogue FRENCH = MessageCatalogue.frenchCanadian();

    private final JsonMapper json;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            var cause = noConnection(ex);
            if (cause == null || response.isCommitted()) {
                throw ex;
            }
            log.warn("Overloaded in a filter, request shed: {}", cause.getMessage());
            shed(request, response);
        }
    }

    /** The connection failure behind {@code ex}, or null when it is something else. */
    static @Nullable Throwable noConnection(Throwable ex) {
        var depth = 0;
        for (Throwable t = ex; t != null && depth < 16; t = t.getCause(), depth++) {
            if (t instanceof DataAccessResourceFailureException || t instanceof SQLTransientConnectionException) {
                return t;
            }
        }
        return null;
    }

    private void shed(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var status = HttpStatus.SERVICE_UNAVAILABLE;
        var problem = ApiExceptionHandler.problem(
                status,
                "overloaded",
                FRENCH.localize(
                        ApiExceptionHandler.OVERLOADED,
                        request.getHeader(HttpHeaders.ACCEPT_LANGUAGE),
                        MessageCatalogue.Arguments.AS_IS));
        if (MessageCatalogue.prefersFrench(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE))) {
            FRENCH.french(status.getReasonPhrase(), MessageCatalogue.Arguments.AS_IS)
                    .ifPresent(problem::setTitle);
        }
        response.resetBuffer();
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ApiExceptionHandler.OVERLOADED_RETRY_AFTER_S));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        var body = new LinkedHashMap<String, Object>();
        body.put("type", String.valueOf(problem.getType()));
        body.put("title", Objects.requireNonNullElse(problem.getTitle(), status.getReasonPhrase()));
        body.put("status", status.value());
        body.put("detail", Objects.requireNonNullElse(problem.getDetail(), ApiExceptionHandler.OVERLOADED));
        body.put("code", "overloaded");
        response.getOutputStream().write(json.writeValueAsBytes(body));
    }
}
