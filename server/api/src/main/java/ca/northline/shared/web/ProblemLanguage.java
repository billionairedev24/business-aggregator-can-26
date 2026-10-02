package ca.northline.shared.web;

import ca.northline.platform.MessageCatalogue;
import ca.northline.shared.PlaceNames;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * S-116 (Loi 96 readiness): every {@link ProblemDetail} the api answers — the shared handler's, each module's own
 * advice (403, 429, 503) and Spring MVC's (405, 415, 400) — goes out in the caller's language. When
 * {@code Accept-Language} prefers French, the title (the HTTP reason phrase) and the detail are looked up in the S-40
 * catalogue ({@code docs/spec/validation-messages.fr-CA.tsv}): {@code Not Found} → {@code Introuvable}, {@code No order
 * with id …} → {@code Aucune commande avec l’identifiant …}. A detail the catalogue doesn't know stays as it is (an
 * already-French detail, an id); English callers get the bytes they always got.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
class ProblemLanguage implements ResponseBodyAdvice<Object> {

    private static final MessageCatalogue FRENCH = MessageCatalogue.frenchCanadian();

    private final ObjectProvider<PlaceNames> placeNames;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true; // the body's type decides (handlers return ProblemDetail, ResponseEntity<ProblemDetail> or Object)
    }

    @Override
    public @Nullable Object beforeBodyWrite(
            @Nullable Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem) {
            var language = request.getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE);
            if (MessageCatalogue.prefersFrench(language)) {
                translate(problem);
            }
        }
        return body;
    }

    /** The French title and detail, where the catalogue has them. */
    void translate(ProblemDetail problem) {
        var title = problem.getTitle();
        if (title != null) {
            FRENCH.french(title, MessageCatalogue.Arguments.AS_IS).ifPresent(problem::setTitle);
        }
        var detail = problem.getDetail();
        if (detail != null) {
            FRENCH.french(detail, this::place).ifPresent(problem::setDetail);
        }
    }

    private String place(String value, @Nullable String form) {
        var in = "in".equals(form);
        var names = placeNames.getIfAvailable();
        var french = names == null ? Optional.<String>empty() : names.french(value, in);
        return french.orElse(in ? "à " + value : value);
    }
}
