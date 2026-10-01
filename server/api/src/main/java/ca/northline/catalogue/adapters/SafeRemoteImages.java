package ca.northline.catalogue.adapters;

import ca.northline.catalogue.application.RemoteImages;
import ca.northline.catalogue.domain.ListingMessages;
import ca.northline.platform.EgressDnsResolver;
import ca.northline.platform.EgressPolicy;
import ca.northline.platform.HostResolver;
import ca.northline.shared.Bytes;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;

/**
 * {@link RemoteImages} under the S-33 egress rules ({@link EgressPolicy}, shared with the worker's webhooks): the URL is
 * checked (https, no credentials, IP literals), every resolved address is checked and the connection pinned to them, no
 * redirects, cookies or retries; 5 s to connect, 10 s per read, 20 s in all; at most 15 MB read (the image upload
 * limit) — more is refused without reading on.
 */
@Slf4j
class SafeRemoteImages implements RemoteImages, AutoCloseable {

    static final Duration CONNECT = Duration.ofSeconds(5);
    static final Duration READ = Duration.ofSeconds(10);
    static final Duration TOTAL = Duration.ofSeconds(20);

    private final EgressPolicy policy;
    private final CloseableHttpClient client;

    SafeRemoteImages(EgressPolicy policy, HostResolver resolver) {
        this.policy = policy;
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new EgressDnsResolver(policy, resolver))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout(CONNECT))
                        .setSocketTimeout(timeout(READ))
                        .build())
                .setDefaultSocketConfig(
                        SocketConfig.custom().setSoTimeout(timeout(READ)).build())
                .setMaxConnTotal(16)
                .setMaxConnPerRoute(4)
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .setUserAgent("Northline-Import/1.0 (+https://northline.ca)")
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(timeout(READ))
                        .setConnectionRequestTimeout(timeout(CONNECT))
                        .setRedirectsEnabled(false)
                        .build())
                .build();
    }

    @Override
    public Result fetch(URI url) {
        var refused = policy.refuseUrl(url).or(() -> policy.refuseLiteral(url.getHost()));
        if (refused.isPresent()) {
            return new Result.Refused(refused.get());
        }
        var request = new HttpGet(url);
        request.setHeader("Accept", "image/jpeg, image/png");
        var deadline = CompletableFuture.runAsync(
                request::cancel, CompletableFuture.delayedExecutor(TOTAL.toMillis(), TimeUnit.MILLISECONDS));
        ClassicHttpResponse response = null;
        try {
            response = client.executeOpen(null, request, null);
            if (response.getCode() != 200) {
                return new Result.Unreachable("HTTP " + response.getCode());
            }
            var entity = response.getEntity();
            if (entity == null) {
                return new Result.Unreachable("empty response");
            }
            var in = entity.getContent();
            var bytes = in.readNBytes((int) ListingMessages.IMAGE_MAX_BYTES + 1);
            if (bytes.length > ListingMessages.IMAGE_MAX_BYTES) {
                request.cancel(); // over the cap: drop the connection instead of draining the rest
                return new Result.Unreachable("larger than 15 MB");
            }
            return new Result.Fetched(Bytes.of(bytes));
        } catch (EgressDnsResolver.Refused e) {
            return new Result.Refused(java.util.Objects.requireNonNullElse(e.getMessage(), "refused address"));
        } catch (IOException | RuntimeException e) {
            log.debug("Import image {} not fetched: {}", url, e.toString());
            return new Result.Unreachable(e.getClass().getSimpleName());
        } finally {
            deadline.cancel(false);
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    log.debug("Closing the import image response failed: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    private static Timeout timeout(Duration d) {
        return Timeout.ofMilliseconds(d.toMillis());
    }
}
