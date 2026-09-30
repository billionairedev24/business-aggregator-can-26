package ca.northline.catalogue.adapters.commerce;

import ca.northline.catalogue.application.ImageFetcher;
import ca.northline.catalogue.domain.ListingMessages;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Downloads platform product images (S-35) without becoming an open proxy: HTTPS only, the platforms' image hosts only
 * ({@code northline.commerce.images.hosts}, exact or a subdomain), no redirects, 20 s, at most 15 MB read.
 */
@Slf4j
class HttpImageFetcher implements ImageFetcher {

    private final List<String> hosts;
    private final boolean allowHttp;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    HttpImageFetcher(CommerceProperties.Images config) {
        this.hosts = config.hosts().stream()
                .map(h -> h.strip().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isEmpty())
                .toList();
        this.allowHttp = config.allowHttp();
    }

    boolean allowed(URI url) {
        var scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        var host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || (allowHttp && "http".equals(scheme))) || url.getUserInfo() != null) {
            return false;
        }
        return hosts.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
    }

    @Override
    public Optional<byte[]> fetch(URI url) {
        if (!allowed(url)) {
            log.debug("Image {} not fetched: host not allowed", url);
            return Optional.empty();
        }
        try {
            var response = http.send(
                    HttpRequest.newBuilder(url)
                            .timeout(Duration.ofSeconds(20))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var in = response.body()) {
                if (response.statusCode() != 200) {
                    return Optional.empty();
                }
                var bytes = in.readNBytes((int) ListingMessages.IMAGE_MAX_BYTES + 1);
                return bytes.length > ListingMessages.IMAGE_MAX_BYTES ? Optional.empty() : Optional.of(bytes);
            }
        } catch (IOException e) {
            log.info("Image {} not fetched: {}", url, e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
