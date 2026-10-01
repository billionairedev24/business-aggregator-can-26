package ca.northline.bff;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * S-130: the Studio assistant streams its answer as server-sent events through the BFF's Gateway MVC relay. Gateway MVC
 * flushes {@code text/event-stream} responses as they arrive ({@code spring.cloud.gateway.mvc.streaming-media-types});
 * this proves it on a real server: the first frame reaches the browser while the api is still writing the second.
 * (Guest browsing of the consumer profile, so no sign-in is needed to reach the relay.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "consumer"})
class SseRelayTest {

    static final long PAUSE_MS = 1_500;
    static final HttpServer API;

    static {
        try {
            API = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        API.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        API.createContext("/api/v1/stream-test", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (var out = ex.getResponseBody()) {
                out.write("event: delta\ndata: {\"text\":\"first\"}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(PAUSE_MS);
                out.write("event: done\ndata: {\"content\":\"first second\"}\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // S-88: the customer's live order tracking (event "order" on every courier move) takes the same relay
        API.createContext("/api/v1/me/orders/o1/events", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (var out = ex.getResponseBody()) {
                out.write("event: order\ndata: {\"state\":\"picked_up\"}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(PAUSE_MS);
                out.write("event: order\ndata: {\"state\":\"delivered\"}\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        API.start();
    }

    @DynamicPropertySource
    static void api(DynamicPropertyRegistry registry) {
        registry.add(
                "northline.bff.api-uri",
                () -> "http://127.0.0.1:" + API.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        API.stop(0);
    }

    @LocalServerPort
    int port;

    @Test
    void eventsReachTheBrowserAsTheyAreWritten() throws Exception {
        var client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var started = System.nanoTime();
        var res = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/stream-test"))
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type"))
                .hasValueSatisfying(t -> assertThat(t).startsWith("text/event-stream"));
        var arrivals = new ArrayList<Long>();
        var lines = new ArrayList<String>();
        try (var in = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("data:")) {
                    lines.add(line);
                    arrivals.add((System.nanoTime() - started) / 1_000_000);
                }
            }
        }
        assertThat(lines).containsExactly("data: {\"text\":\"first\"}", "data: {\"content\":\"first second\"}");
        assertThat(arrivals.get(0)).as("first frame before the api finished").isLessThan(PAUSE_MS - 300);
        assertThat(arrivals.get(1)).isGreaterThanOrEqualTo(PAUSE_MS - 100);
    }

    @Test
    void theOrderTrackingStreamIsRelayedAsItIsWritten() throws Exception {
        var client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var started = System.nanoTime();
        var res = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me/orders/o1/events"))
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);
        var arrivals = new ArrayList<Long>();
        var lines = new ArrayList<String>();
        try (var in = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("data:")) {
                    lines.add(line);
                    arrivals.add((System.nanoTime() - started) / 1_000_000);
                }
            }
        }
        assertThat(lines).containsExactly("data: {\"state\":\"picked_up\"}", "data: {\"state\":\"delivered\"}");
        assertThat(arrivals.get(0)).as("first position before the api finished").isLessThan(PAUSE_MS - 300);
    }
}
