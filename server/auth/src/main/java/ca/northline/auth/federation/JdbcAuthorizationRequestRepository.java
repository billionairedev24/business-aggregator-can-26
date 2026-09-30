package ca.northline.auth.federation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

/**
 * Authorization requests to Google / Apple kept by {@code state} in {@code auth.federation_requests} (V022) instead of
 * the HTTP session: Apple answers with a cross-site form POST, which doesn't carry the SameSite=Lax session cookie.
 * The state is unguessable (Spring's 32-byte random), used once and valid 10 minutes; shared by every replica.
 */
final class JdbcAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final Duration TTL = Duration.ofMinutes(10);

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcAuthorizationRequestRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public @Nullable OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        var state = request.getParameter(OAuth2ParameterNames.STATE);
        if (state == null) {
            return null;
        }
        return jdbc.sql("SELECT request FROM auth.federation_requests WHERE state = :s AND expires_at > :now")
                .param("s", state)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .query((rs, _) -> read(rs.getBytes("request")))
                .optional()
                .orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(
            @Nullable OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            return;
        }
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.sql("DELETE FROM auth.federation_requests WHERE expires_at <= :now")
                .param("now", now)
                .update();
        jdbc.sql("INSERT INTO auth.federation_requests (state, request, expires_at) VALUES (:s, :r, :exp)")
                .param("s", authorizationRequest.getState())
                .param("r", write(authorizationRequest))
                .param("exp", now.plus(TTL))
                .update();
    }

    @Override
    public @Nullable OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request, HttpServletResponse response) {
        var found = loadAuthorizationRequest(request);
        if (found != null) {
            jdbc.sql("DELETE FROM auth.federation_requests WHERE state = :s")
                    .param("s", found.getState())
                    .update();
        }
        return found;
    }

    /** Only JDK and Spring Security types (and arrays of them) are read back. */
    private static boolean allowed(@Nullable Class<?> type) {
        if (type == null) {
            return true;
        }
        var element = type;
        while (element.isArray()) {
            element = element.getComponentType();
        }
        var name = element.getName();
        return element.isPrimitive() || name.startsWith("java.") || name.startsWith("org.springframework.security.");
    }

    private static byte[] write(OAuth2AuthorizationRequest request) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(request);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static @Nullable OAuth2AuthorizationRequest read(byte[] bytes) {
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.setObjectInputFilter(info -> allowed(info.serialClass())
                    ? java.io.ObjectInputFilter.Status.ALLOWED
                    : java.io.ObjectInputFilter.Status.REJECTED);
            return in.readObject() instanceof OAuth2AuthorizationRequest r ? r : null;
        } catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }
}
