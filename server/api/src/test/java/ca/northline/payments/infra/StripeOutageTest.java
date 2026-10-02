package ca.northline.payments.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.payments.application.PaymentGateway;
import ca.northline.shared.ProviderUnavailable;
import ca.northline.shared.stripe.StripeClients;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S-115 Stripe outage drill (docs/runbooks/stripe-incidents.md § Stripe is down): when Stripe can't be reached, throttles
 * us or answers 5xx, a checkout's PaymentIntent fails as {@code payments_unavailable} (503 + Retry-After, nothing
 * charged); a refusal of the request itself stays an ordinary failure.
 */
class StripeOutageTest {

    @Test
    void connectionRefused_isAnOutage() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort(); // closed again: nothing listens there
        }
        var gateway = new StripeConnectGateway(StripeClients.create("sk_test_fake_s115", "http://127.0.0.1:" + port));

        assertThatThrownBy(() -> authorize(gateway))
                .isInstanceOf(ProviderUnavailable.class)
                .hasMessage(StripeConnectGateway.StripeUnavailable.MESSAGE)
                .satisfies(e -> {
                    var unavailable = (ProviderUnavailable) e;
                    assertThat(unavailable.getCode()).isEqualTo("payments_unavailable");
                    assertThat(unavailable.getRetryAfterSeconds()).isEqualTo(60);
                    assertThat(unavailable.getCause()).isInstanceOf(StripeConnectGateway.StripeCallFailed.class);
                });
    }

    @Test
    void a5xxIsAnOutage_a4xxIsNot() throws Exception {
        var status = new java.util.concurrent.atomic.AtomicInteger(500);
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            var type = status.get() >= 500 ? "api_error" : "invalid_request_error";
            var body = ("{\"error\":{\"type\":\"" + type + "\",\"message\":\"fake " + status.get() + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Stripe-Should-Retry", "false");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var gateway = new StripeConnectGateway(StripeClients.create(
                    "sk_test_fake_s115",
                    "http://127.0.0.1:" + server.getAddress().getPort()));
            assertThatThrownBy(() -> authorize(gateway)).isInstanceOf(ProviderUnavailable.class);

            status.set(400);
            assertThatThrownBy(() -> authorize(gateway))
                    .isInstanceOf(StripeConnectGateway.StripeCallFailed.class)
                    .isNotInstanceOf(ProviderUnavailable.class);
        } finally {
            server.stop(0);
        }
    }

    private static void authorize(StripeConnectGateway gateway) {
        gateway.authorize(new PaymentGateway.Authorize(
                2_500,
                "order:OR1",
                "cus_fake",
                null,
                false,
                Map.of("northline_escrow_id", "01J9ZD3V00000000000000ESC1"),
                StripeIdempotencyKeys.of("authorize", "order", "OR1")));
    }
}
