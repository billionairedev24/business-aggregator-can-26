package ca.northline.worker.webhooks;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.net.InetAddressUtils;
import org.apache.hc.core5.util.Timeout;
import org.jspecify.annotations.Nullable;

/**
 * {@link WebhookTransport} over Apache HttpClient 5 with the S-33 SSRF rules:
 *
 * <ul>
 *   <li>the URL passes {@link EgressPolicy#refuseUrl} (https, no credentials) before anything is resolved;
 *   <li>the client's {@link DnsResolver} resolves the host, refuses the lot when any address is private, loopback,
 *       link-local, metadata…, and hands the connection exactly the addresses it checked — no second lookup, so DNS
 *       rebinding can't swap the target. TLS still verifies the certificate against the host name (SNI unchanged);
 *   <li>redirects are not followed (a 3xx is a failed attempt), no automatic retries, cookies or auth caching, no
 *       proxies from the environment (a proxy would resolve the name itself);
 *   <li>connect, per-read and total timeouts; the answer is read up to {@code maxResponse} bytes, then the connection
 *       is dropped.
 * </ul>
 */
@Slf4j
public final class HttpWebhookTransport implements WebhookTransport, AutoCloseable {

    private final EgressPolicy policy;
    private final WebhookProperties settings;
    private final CloseableHttpClient client;

    public HttpWebhookTransport(EgressPolicy policy, HostResolver resolver, WebhookProperties settings) {
        this.policy = policy;
        this.settings = settings;
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new CheckingResolver(policy, resolver))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout(settings.connectTimeout()))
                        .setSocketTimeout(timeout(settings.responseTimeout()))
                        .setTimeToLive(Timeout.ofMinutes(5))
                        .build())
                .setDefaultSocketConfig(SocketConfig.custom()
                        .setSoTimeout(timeout(settings.responseTimeout()))
                        .build())
                .setMaxConnTotal(Math.max(8, settings.maxInFlight() * 2))
                .setMaxConnPerRoute(2)
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .setUserAgent(settings.userAgent())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(timeout(settings.responseTimeout()))
                        .setConnectionRequestTimeout(timeout(settings.connectTimeout()))
                        .setRedirectsEnabled(false)
                        .build())
                .build();
    }

    @Override
    public Result post(URI url, Map<String, String> headers, byte[] body) {
        var started = System.nanoTime();
        var refused = policy.refuseUrl(url).or(() -> refuseLiteral(url.getHost()));
        if (refused.isPresent()) {
            return new Result(null, 0, "refused: " + refused.get(), null);
        }
        var request = new HttpPost(url);
        headers.forEach(request::setHeader);
        request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        var timedOut = new AtomicBoolean();
        var deadline = CompletableFuture.runAsync(
                () -> {
                    timedOut.set(true);
                    request.cancel();
                },
                CompletableFuture.delayedExecutor(settings.totalTimeout().toMillis(), TimeUnit.MILLISECONDS));
        ClassicHttpResponse response = null;
        try {
            response = client.executeOpen(null, request, null);
            var code = response.getCode();
            String snippet = null;
            var entity = response.getEntity();
            if (entity != null) {
                var in = entity.getContent();
                var cap = (int)
                        Math.min(Integer.MAX_VALUE - 8, settings.maxResponse().toBytes());
                var bytes = in.readNBytes(cap);
                if (bytes.length == cap && in.read() != -1) {
                    request.cancel(); // over the cap: drop the connection instead of draining the rest
                }
                snippet = snippet(bytes);
            }
            return new Result(code, elapsed(started), null, snippet);
        } catch (RefusedAddress e) {
            return new Result(null, elapsed(started), "refused: " + e.getMessage(), null);
        } catch (IOException | RuntimeException e) {
            return new Result(null, elapsed(started), describe(e, timedOut.get()), null);
        } finally {
            deadline.cancel(false);
            closeQuietly(response);
        }
    }

    /** An IP literal is checked here as well: the client may connect to a literal without asking the resolver. */
    private Optional<String> refuseLiteral(String host) {
        var bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!InetAddressUtils.isIPv4(bare) && !InetAddressUtils.isIPv6(bare)) {
            return Optional.empty();
        }
        try {
            return policy.refuseAddresses(bare, List.of(InetAddress.getByName(bare)));
        } catch (UnknownHostException e) {
            return Optional.of(host + " is not a valid address");
        }
    }

    private static void closeQuietly(@Nullable ClassicHttpResponse response) {
        if (response == null) {
            return;
        }
        try {
            response.close();
        } catch (IOException | RuntimeException e) {
            log.debug("closing a webhook response: {}", e.toString());
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    private @Nullable String snippet(byte[] bytes) {
        if (bytes.length == 0) {
            return null;
        }
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        String text;
        try {
            text = decoder.decode(ByteBuffer.wrap(bytes, 0, Math.min(bytes.length, settings.snippetChars() * 4)))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        var clean = text.replaceAll("[\\p{Cc}&&[^\\n\\t]]", " ").strip();
        if (clean.length() > settings.snippetChars()) {
            clean = clean.substring(0, settings.snippetChars());
        }
        return clean.isEmpty() ? null : clean;
    }

    private String describe(Exception e, boolean timedOut) {
        if (timedOut) {
            return "timed out after " + settings.totalTimeout().toSeconds() + " s";
        }
        return switch (e) {
            case SocketTimeoutException _ ->
                "timed out (no answer within " + settings.responseTimeout().toSeconds() + " s)";
            case ConnectException _, NoRouteToHostException _ -> "connection refused";
            case UnknownHostException _ -> "host not found";
            case SSLException ssl -> "TLS error: " + ssl.getMessage();
            case InterruptedIOException _ -> "interrupted";
            default -> e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        };
    }

    private static int elapsed(long started) {
        return (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private static Timeout timeout(Duration duration) {
        return Timeout.ofMilliseconds(duration.toMillis());
    }

    /** The SSRF check at resolution time; its message is the delivery log's reason. */
    static final class RefusedAddress extends UnknownHostException {
        private static final long serialVersionUID = 1L;

        RefusedAddress(String reason) {
            super(reason);
        }
    }

    /** Resolves, checks every address, and returns only checked ones (the connection uses exactly these). */
    private record CheckingResolver(EgressPolicy policy, HostResolver resolver) implements DnsResolver {

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            var addresses = resolver.resolve(host);
            var refused = policy.refuseAddresses(host, addresses);
            if (refused.isPresent()) {
                throw new RefusedAddress(refused.get());
            }
            return addresses.toArray(InetAddress[]::new);
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host; // never used for the connection; no reverse lookups
        }
    }
}
