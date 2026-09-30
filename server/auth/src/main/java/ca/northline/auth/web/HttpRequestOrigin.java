package ca.northline.auth.web;

import ca.northline.auth.application.RequestOrigin;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@link RequestOrigin} from the current request ({@code HttpServletRequest} is a request-scoped proxy). The remote
 * address is already the client's: {@code TrustedProxyFilter} resolved {@code X-Forwarded-For} from trusted proxies.
 */
@Component
@RequiredArgsConstructor
class HttpRequestOrigin implements RequestOrigin {

    private final HttpServletRequest request;

    @Override
    public @Nullable String ip() {
        return request.getRemoteAddr();
    }

    @Override
    public String sessionId() {
        return request.getSession(true).getId();
    }
}
