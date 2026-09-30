package ca.northline.merchants.integration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** {@code @HttpExchange} clients for the registry adapters: JDK HTTP client, connect 5 s, read 15 s. */
final class RegistryHttp {
    private RegistryHttp() {}

    static <T> T client(Class<T> api, String baseUrl, Consumer<HttpHeaders> headers) {
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
        requests.setReadTimeout(Duration.ofSeconds(15));
        var rest = RestClient.builder()
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .defaultHeaders(headers)
                .requestFactory(requests)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(api);
    }
}
