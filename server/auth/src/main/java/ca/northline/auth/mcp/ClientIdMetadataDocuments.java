package ca.northline.auth.mcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth Client ID Metadata Documents (draft-ietf-oauth-client-id-metadata-document, the MCP specification's way for a
 * client and an authorization server with no prior relationship): when an authorization request's {@code client_id} is
 * an HTTPS URL, the client is described by the JSON document at that URL. This repository fetches it (no redirects,
 * a time-out, a size cap, only public addresses — no request into the cluster), checks it — {@code client_id} equals
 * the URL, a name, redirect URIs that are HTTPS or loopback (RFC 8252), no client secret — and registers a public,
 * PKCE-only client that needs the person's consent, with at most the MCP scopes. The registration is stored (Spring
 * Authorization Server looks clients up by id when it reads authorizations back) and refreshed from the document after
 * {@code cache-for}. Every other client id goes to the configured clients ({@code delegate}).
 *
 * <p>Dynamic Client Registration (RFC 7591) stays off: an open registration endpoint would let anyone create clients
 * here; a metadata document is owned by the client's domain and changes there.
 */
@Slf4j
public class ClientIdMetadataDocuments implements RegisteredClientRepository {

    private static final String ID_PREFIX = "cimd-";

    private final RegisteredClientRepository delegate;
    private final McpAuthProperties.MetadataDocuments props;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpClient http;
    private final ConcurrentHashMap<String, Instant> fetchedAt = new ConcurrentHashMap<>();

    public ClientIdMetadataDocuments(
            RegisteredClientRepository delegate,
            McpAuthProperties.MetadataDocuments props,
            JsonMapper json,
            Clock clock) {
        this.delegate = delegate;
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(props.timeout())
                .build();
    }

    /** A {@code client_id} that names a metadata document: an absolute HTTPS URL with a path. */
    public static boolean isMetadataUrl(@Nullable String clientId) {
        return clientId != null && (clientId.startsWith("https://") || clientId.startsWith("http://"));
    }

    @Override
    public void save(RegisteredClient client) {
        delegate.save(client);
    }

    @Override
    public @Nullable RegisteredClient findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public @Nullable RegisteredClient findByClientId(String clientId) {
        if (!props.enabled() || !isMetadataUrl(clientId)) {
            return delegate.findByClientId(clientId);
        }
        var stored = delegate.findByClientId(clientId);
        var fetched = fetchedAt.get(clientId);
        if (stored != null && fetched != null && fetched.plus(props.cacheFor()).isAfter(clock.instant())) {
            return stored;
        }
        try {
            var client = register(clientId, stored);
            delegate.save(client);
            fetchedAt.put(clientId, clock.instant());
            return client;
        } catch (InvalidDocument e) {
            log.warn("Client ID Metadata Document refused: {} — {}", clientId, e.getMessage());
            return null;
        }
    }

    private RegisteredClient register(String url, @Nullable RegisteredClient stored) throws InvalidDocument {
        var document = fetch(url);
        if (!url.equals(document.path("client_id").asString(""))) {
            throw new InvalidDocument("client_id in the document must equal its URL");
        }
        var name = document.path("client_name").asString("").strip();
        if (name.isEmpty()) {
            throw new InvalidDocument("client_name is required");
        }
        var redirects = new ArrayList<String>();
        document.path("redirect_uris").forEach(u -> redirects.add(u.asString("")));
        if (redirects.isEmpty()) {
            throw new InvalidDocument("redirect_uris is required");
        }
        for (var redirect : redirects) {
            checkRedirect(redirect);
        }
        var method = document.path("token_endpoint_auth_method").asString("none");
        if (!"none".equals(method)) {
            throw new InvalidDocument("token_endpoint_auth_method must be none (public client with PKCE)");
        }
        for (var grant : document.path("grant_types")) {
            if (!List.of("authorization_code", "refresh_token").contains(grant.asString(""))) {
                throw new InvalidDocument("grant type " + grant.asString("") + " is not allowed");
            }
        }
        return RegisteredClient.withId(stored == null ? ID_PREFIX + hash(url) : stored.getId())
                .clientId(url)
                .clientIdIssuedAt(issuedAt(stored))
                .clientName(name.length() > 100 ? name.substring(0, 100) : name)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUris(u -> u.addAll(redirects))
                .scopes(s -> s.addAll(props.scopes()))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(props.accessTokenTtl())
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.ES256)
                        .build())
                .build();
    }

    private Instant issuedAt(@Nullable RegisteredClient stored) {
        var issued = stored == null ? null : stored.getClientIdIssuedAt();
        return issued == null ? clock.instant() : issued;
    }

    private JsonNode fetch(String url) throws InvalidDocument {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new InvalidDocument("not a URL");
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(props.allowInsecure() && "http".equals(scheme))) {
            throw new InvalidDocument("the client id must be an https URL");
        }
        if (uri.getHost() == null
                || uri.getRawPath() == null
                || uri.getRawPath().length() <= 1
                || uri.getRawFragment() != null
                || uri.getUserInfo() != null) {
            throw new InvalidDocument("the client id must be an https URL with a path, without fragment or user info");
        }
        if (url.length() > 2000) {
            throw new InvalidDocument("the client id is longer than 2000 characters");
        }
        var allowed = props.allowedHosts().stream()
                .map(h -> h.strip().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isEmpty())
                .toList();
        if (!allowed.isEmpty() && !allowed.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new InvalidDocument(
                    "host " + uri.getHost() + " is not in northline.auth.mcp.metadata-documents.allowed-hosts");
        }
        if (!props.allowInsecure()) {
            requirePublicAddress(uri.getHost());
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(props.timeout())
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new InvalidDocument("fetch failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidDocument("fetch interrupted");
        }
        if (response.statusCode() != 200) {
            throw new InvalidDocument("HTTP " + response.statusCode());
        }
        if (response.body().length > props.maxBytes()) {
            throw new InvalidDocument("larger than " + props.maxBytes() + " bytes");
        }
        try {
            var node = json.readTree(new String(response.body(), StandardCharsets.UTF_8));
            if (node == null || !node.isObject()) {
                throw new InvalidDocument("not a JSON object");
            }
            return node;
        } catch (RuntimeException e) {
            throw new InvalidDocument("not JSON: " + e.getMessage());
        }
    }

    /** RFC 8252: HTTPS, a loopback IP literal over http (native apps), or a reverse-domain private-use scheme. */
    private void checkRedirect(String redirect) throws InvalidDocument {
        URI uri;
        try {
            uri = URI.create(redirect);
        } catch (IllegalArgumentException e) {
            throw new InvalidDocument("redirect URI " + redirect + " is not a URI");
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        var ok = switch (scheme) {
            case "https" -> uri.getHost() != null;
            case "http" ->
                "127.0.0.1".equals(uri.getHost())
                        || "[::1]".equals(uri.getHost())
                        || (props.allowInsecure() && "localhost".equals(uri.getHost()));
            default -> scheme.contains(".");
        };
        if (!ok || uri.getRawFragment() != null || redirect.contains("*")) {
            throw new InvalidDocument("redirect URI " + redirect + " must be https, http on a loopback IP, or a"
                    + " reverse-domain scheme, without wildcard or fragment");
        }
    }

    /** No document fetched from inside the cluster or the cloud's metadata service (SSRF). */
    private static void requirePublicAddress(String host) throws InvalidDocument {
        try {
            for (var address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress()
                        || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress()
                        || address.isMulticastAddress()
                        || isUniqueLocal(address)) {
                    throw new InvalidDocument(host + " resolves to a private address");
                }
            }
        } catch (UnknownHostException e) {
            throw new InvalidDocument("unknown host " + host);
        }
    }

    private static boolean isUniqueLocal(InetAddress address) {
        var bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Why a metadata document was refused (logged; the authorization request then fails as for an unknown client). */
    static final class InvalidDocument extends Exception {
        InvalidDocument(String message) {
            super(message);
        }
    }
}
