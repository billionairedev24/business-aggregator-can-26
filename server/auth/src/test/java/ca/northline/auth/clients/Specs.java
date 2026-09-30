package ca.northline.auth.clients;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Test builders for {@link ClientSpec} / {@link OAuthClientProperties}. */
final class Specs {

    private Specs() {}

    static ClientSpec bff(@Nullable String secretHash, String... redirectUris) {
        return new ClientSpec(
                ClientSpec.Type.CONFIDENTIAL,
                false,
                null,
                secretHash,
                List.of(redirectUris),
                List.of(),
                List.of("openid", "profile"),
                List.of("authorization_code", "refresh_token"),
                true,
                false,
                Duration.ofMinutes(10),
                Duration.ofHours(12),
                false);
    }

    static ClientSpec mobile(String... redirectUris) {
        return new ClientSpec(
                ClientSpec.Type.PUBLIC,
                false,
                null,
                null,
                List.of(redirectUris),
                List.of(),
                List.of("openid", "offline_access"),
                List.of("authorization_code", "refresh_token"),
                true,
                false,
                Duration.ofMinutes(10),
                Duration.ofDays(30),
                true);
    }

    static OAuthClientProperties props(Object... idsAndSpecs) {
        var clients = new LinkedHashMap<String, ClientSpec>();
        for (var i = 0; i < idsAndSpecs.length; i += 2) {
            clients.put((String) idsAndSpecs[i], (ClientSpec) idsAndSpecs[i + 1]);
        }
        return new OAuthClientProperties(true, Map.copyOf(clients));
    }
}
