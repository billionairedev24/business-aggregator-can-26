package ca.northline.catalogue.adapters.commerce;

import ca.northline.catalogue.application.CommerceCatalogSource.GrantRevoked;
import ca.northline.shared.integration.ProviderHttp;
import java.net.URI;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared platform plumbing ({@link ProviderHttp}, S-36 moved it to {@code shared.integration}) with this port's
 * {@link GrantRevoked} for refused grants.
 */
final class CommerceHttp {
    private CommerceHttp() {}

    static final JsonMapper JSON = ProviderHttp.JSON;

    static <T> T client(Class<T> api) {
        return ProviderHttp.client(api);
    }

    static URI uri(String base, String path, Map<String, String> query) {
        return ProviderHttp.uri(base, path, query);
    }

    static Map<String, String> params() {
        return ProviderHttp.params();
    }

    static String query(Map<String, String> params) {
        return ProviderHttp.query(params);
    }

    static String encode(String s) {
        return ProviderHttp.encode(s);
    }

    static MultiValueMap<String, String> form(String... pairs) {
        return ProviderHttp.form(pairs);
    }

    static byte[] hmacSha256(String key, byte[] data) {
        return ProviderHttp.hmacSha256(key, data);
    }

    static boolean same(@Nullable String presented, String expected) {
        return ProviderHttp.same(presented, expected);
    }

    static @Nullable String text(JsonNode node, String field) {
        return ProviderHttp.text(node, field);
    }

    static long cents(JsonNode amount) {
        return ProviderHttp.cents(amount);
    }

    static JsonNode tokenCall(Supplier<JsonNode> call) {
        return ProviderHttp.tokenCall(call, GrantRevoked::new);
    }

    static <T> T authorized(Supplier<T> call) {
        return ProviderHttp.authorized(call, GrantRevoked::new);
    }
}
