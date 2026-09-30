package ca.northline.auth.dpop;

import ca.northline.auth.clients.RegisteredClients;
import ca.northline.auth.replay.ReplayStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * In front of {@code POST /oauth2/token} (S-29), before Spring Authorization Server reads the request:
 *
 * <ul>
 *   <li>a client with {@code dpop-required} must send exactly one {@code DPoP} proof on every token request
 *       (authorization code and refresh) — without it Spring would issue plain bearer tokens;
 *   <li>any proof must carry a current server nonce ({@code 400 use_dpop_nonce} otherwise) and a {@code jti} never seen
 *       by any instance (Valkey); the rest of the proof is checked by the same Spring Security code Spring
 *       Authorization Server then runs again;
 *   <li>every answer carries the current nonce in {@code DPoP-Nonce};
 *   <li>the store unreachable = {@code 503 temporarily_unavailable} (a proof that can't be checked for replay is
 *       refused, whatever {@code RATE_LIMIT_WHEN_UNAVAILABLE} says).
 * </ul>
 */
@Slf4j
final class DpopTokenEndpointFilter extends OncePerRequestFilter {

    static final String NONCE_HEADER = "DPoP-Nonce";
    static final String PROOF_HEADER = "DPoP";

    private final DpopProofs proofs;
    private final DpopNonces nonces;
    private final RegisteredClientRepository clients;

    DpopTokenEndpointFilter(DpopProofs proofs, DpopNonces nonces, RegisteredClientRepository clients) {
        this.proofs = proofs;
        this.nonces = nonces;
        this.clients = clients;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !"/oauth2/token".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = Collections.list(request.getHeaders(PROOF_HEADER));
        var client = clientId(request);
        var registered = client == null ? null : clients.findByClientId(client);
        var required = registered != null && RegisteredClients.dpopRequired(registered);
        if (headers.isEmpty() && !required) {
            chain.doFilter(request, response);
            return;
        }
        try {
            response.setHeader(NONCE_HEADER, nonces.current());
            if (headers.size() != 1) {
                refuse(
                        response,
                        OAuth2ErrorCodes.INVALID_DPOP_PROOF,
                        headers.isEmpty() ? "This client must send a DPoP proof." : "Send exactly one DPoP proof.");
                return;
            }
            proofs.verify(
                    headers.getFirst(),
                    request.getMethod(),
                    request.getRequestURL().toString());
        } catch (DpopProofs.Rejected e) {
            log.debug("DPoP proof refused at the token endpoint (client {}): {}", client, e.error());
            refuse(response, e.error(), e.getMessage());
            return;
        } catch (ReplayStore.Unavailable e) {
            log.error("Replay store unavailable (Valkey): token request refused — {}", e.getMessage());
            response.setHeader(HttpHeaders.RETRY_AFTER, "30");
            write(response, HttpStatus.SERVICE_UNAVAILABLE, "temporarily_unavailable", "Try again in a moment.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** {@code client_id} of a public client (form parameter) or of HTTP Basic client authentication. */
    private static @Nullable String clientId(HttpServletRequest request) {
        var param = request.getParameter(OAuth2ParameterNames.CLIENT_ID);
        if (param != null) {
            return param;
        }
        var authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            var decoded = new String(
                    Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
            var colon = decoded.indexOf(':');
            return colon < 0 ? null : URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static void refuse(HttpServletResponse response, String error, @Nullable String description)
            throws IOException {
        write(response, HttpStatus.BAD_REQUEST, error, description);
    }

    private static void write(
            HttpServletResponse response, HttpStatus status, String error, @Nullable String description)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter()
                .write("{\"error\":\"" + error + "\",\"error_description\":\""
                        + (description == null ? "" : description.replace("\"", "'")) + "\"}");
    }
}
