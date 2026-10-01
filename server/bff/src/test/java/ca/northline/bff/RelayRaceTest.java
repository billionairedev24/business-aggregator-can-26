package ca.northline.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-135: the api relay under concurrent load, in a context that has generated its OpenAPI document and served the docs
 * viewers (the conditions under which S-125 saw {@link ConsumerBffTest} fail 4 of 6 runs).
 *
 * <p>Before the fix, a relayed request without a body went out as an empty streamed body. When it ran on a pooled
 * connection the api had already closed, the JDK HttpClient failed it with an NPE in
 * {@code Http1Exchange.requestMoreBody} (DECISIONS, S-135). {@link #relayedGets_onConnectionsTheApiClosed_neverFail}
 * failed on every run before the fix; {@link #relayedBodies_arriveByteForByte} checks that the look-ahead
 * ({@code RelayBody}) forwards every body exactly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "consumer"})
class RelayRaceTest {

    static final int REQUESTS = 400;
    static final int WORKERS = 4;
    static final Duration PAUSE = Duration.ofMillis(3);
    static final String TOKEN = "fake-xsrf-s135";
    static final String JSON = "{\"listingId\":\"01J9ZD3V00000000000000PWP1\"}";
    static final String LARGE = "x".repeat(5_000);

    /**
     * The api: plain HTTP/1.1 that answers every path with the method and the number of body bytes it received. While
     * {@code closesKeptAliveConnections} is set, it closes each connection after its response without a
     * {@code Connection: close} header, as an api whose keep-alive timeout runs out does. The relay's pool then hands
     * connections the api has already closed to later requests: that is when the JDK client lost the race.
     */
    static final ServerSocket API;

    static volatile boolean closesKeptAliveConnections;

    static {
        try {
            API = new ServerSocket(0, 200, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Thread.ofVirtual().start(() -> {
            while (!API.isClosed()) {
                try {
                    var socket = API.accept();
                    Thread.ofVirtual().start(() -> serve(socket));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    @DynamicPropertySource
    static void api(DynamicPropertyRegistry registry) {
        registry.add("northline.bff.api-uri", () -> "http://127.0.0.1:" + API.getLocalPort());
    }

    @AfterAll
    static void stop() throws IOException {
        API.close();
    }

    @Autowired
    MockMvc mvc;

    @LocalServerPort
    int port;

    @BeforeEach
    void docsViewersInUse() throws Exception {
        for (var path : List.of(
                "/bff/v3/api-docs/internal",
                "/bff/v3/api-docs/swagger-config",
                "/bff/swagger-ui/index.html",
                "/bff/swagger-ui/swagger-ui-bundle.js",
                "/bff/docs/scalar")) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
                    .as(path)
                    .isEqualTo(200);
        }
    }

    /**
     * GETs only: the JDK client retries an idempotent request whose pooled connection turns out to be closed, but not a
     * POST or DELETE (that is HTTP, not this race; see DECISIONS, S-135).
     */
    @Test
    void relayedGets_onConnectionsTheApiClosed_neverFail() throws Exception {
        closesKeptAliveConnections = true;
        hammer(i -> switch (i % 3) {
            case 0 -> relay(get("/api/v1/search").param("q", "r" + i), "GET", 0);
            case 1 -> raw("GET", null, 0);
            default -> raw("GET", "0\r\n\r\n", 0); // empty chunked body: Tomcat's way into the same path
        });
    }

    @Test
    void relayedBodies_arriveByteForByte() throws Exception {
        closesKeptAliveConnections = false;
        var client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        hammer(i -> switch (i % 7) {
            case 0 -> relay(csrf(delete("/api/v1/cart/items/" + i)), "DELETE", 0);
            case 1 -> relay(csrf(post("/api/v1/cart/items")), "POST", 0);
            case 2 ->
                relay(
                        csrf(post("/api/v1/cart/items"))
                                .contentType("application/json")
                                .content(JSON),
                        "POST",
                        JSON.length());
            case 3 -> raw("POST", "0\r\n\r\n", 0);
            case 4 -> raw("POST", chunked(JSON.substring(0, 1), JSON.substring(1)), JSON.length());
            case 5 -> raw("POST", chunked(LARGE.substring(0, 2_000), LARGE.substring(2_000)), LARGE.length());
            default ->
                () -> {
                    var res = client.send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/cart/items"))
                                    .header("Cookie", "XSRF-TOKEN=" + TOKEN)
                                    .header("X-XSRF-TOKEN", TOKEN)
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(LARGE))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertThat(res.statusCode()).isEqualTo(200);
                    assertThat(res.body()).isEqualTo(echo("POST", LARGE.length()));
                    return null;
                };
        });
    }

    private static MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", TOKEN)).header("X-XSRF-TOKEN", TOKEN);
    }

    private Callable<Void> relay(MockHttpServletRequestBuilder request, String method, int bytes) {
        return () -> {
            var res = mvc.perform(request).andReturn().getResponse();
            assertThat(res.getStatus()).isEqualTo(200);
            assertThat(res.getContentAsString()).isEqualTo(echo(method, bytes));
            return null;
        };
    }

    private static String chunked(String... chunks) {
        var body = new StringBuilder();
        for (var chunk : chunks) {
            body.append(Integer.toHexString(chunk.length()))
                    .append("\r\n")
                    .append(chunk)
                    .append("\r\n");
        }
        return body.append("0\r\n\r\n").toString();
    }

    /** A hand-written HTTP/1.1 request, so the body can be chunked (and empty), which no client library sends. */
    private Callable<Void> raw(String method, @Nullable String chunkedBody, int bytes) {
        return () -> {
            var head = method + " /api/v1/cart/items HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + "Cookie: XSRF-TOKEN=" + TOKEN + "\r\nX-XSRF-TOKEN: " + TOKEN + "\r\n"
                    + (chunkedBody == null
                            ? "\r\n"
                            : "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n" + chunkedBody);
            try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(response).startsWith("HTTP/1.1 200").contains(echo(method, bytes));
            }
            return null;
        };
    }

    /**
     * {@value #WORKERS} browsers sending {@value #REQUESTS} requests between them, each a few milliseconds after its
     * previous one: long enough for the api to have closed the connection the relay just put back in its pool.
     */
    private static void hammer(IntFunction<Callable<Void>> request) throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var workers = IntStream.range(0, WORKERS)
                    .mapToObj(w -> pool.submit(() -> {
                        for (var i = w; i < REQUESTS; i += WORKERS) {
                            request.apply(i).call();
                            Thread.sleep(PAUSE);
                        }
                        return null;
                    }))
                    .toList();
            for (var worker : workers) {
                worker.get();
            }
        }
    }

    // ── the api stand-in
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    static String echo(String method, int bytes) {
        return "{\"method\":\"%s\",\"bytes\":%d}".formatted(method, bytes);
    }

    private static void serve(Socket socket) {
        try (socket) {
            var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            do {
                answer(in, socket);
            } while (!closesKeptAliveConnections);
        } catch (IOException e) {
            // the relay closed its end (or gave up on it); its own assertions report any failure
        }
    }

    private static void answer(DataInputStream in, Socket socket) throws IOException {
        var method = line(in).split(" ")[0];
        var length = 0;
        var chunked = false;
        for (var header = line(in); !header.isEmpty(); header = line(in)) {
            var lower = header.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                length = Integer.parseInt(
                        lower.substring("content-length:".length()).trim());
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }
        var received = 0;
        if (chunked) {
            for (var size = Integer.parseInt(line(in), 16); size > 0; size = Integer.parseInt(line(in), 16)) {
                in.skipNBytes(size + 2L);
                received += size;
            }
            line(in);
        } else {
            in.skipNBytes(length);
            received = length;
        }
        var body = echo(method, received);
        socket.getOutputStream()
                .write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
                                + "\r\n\r\n" + body)
                        .getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static String line(DataInputStream in) throws IOException {
        var line = new StringBuilder();
        for (var c = in.read(); c != '\n'; c = in.read()) {
            if (c < 0) {
                throw new EOFException();
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }
}
