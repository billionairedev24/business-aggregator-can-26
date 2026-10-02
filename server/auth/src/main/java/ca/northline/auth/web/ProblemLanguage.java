package ca.northline.auth.web;

import ca.northline.platform.MessageCatalogue;
import org.jspecify.annotations.Nullable;
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
 * S-116: every {@link ProblemDetail} northline-auth answers goes out in the caller's language — the title (the HTTP
 * reason phrase) and the detail looked up in the S-40 catalogue when {@code Accept-Language} prefers French. English
 * callers get the bytes they always got. Same rule as the api's {@code shared.web.ProblemLanguage}.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class ProblemLanguage implements ResponseBodyAdvice<Object> {

    private static final MessageCatalogue FRENCH = MessageCatalogue.frenchCanadian();

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public @Nullable Object beforeBodyWrite(
            @Nullable Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem
                && MessageCatalogue.prefersFrench(request.getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE))) {
            var title = problem.getTitle();
            if (title != null) {
                FRENCH.french(title, MessageCatalogue.Arguments.AS_IS).ifPresent(problem::setTitle);
            }
            var detail = problem.getDetail();
            if (detail != null) {
                FRENCH.french(detail, MessageCatalogue.Arguments.AS_IS).ifPresent(problem::setDetail);
            }
        }
        return body;
    }
}
