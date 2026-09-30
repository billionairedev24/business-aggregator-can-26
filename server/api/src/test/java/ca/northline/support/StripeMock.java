package ca.northline.support;

import ca.northline.shared.stripe.StripeClients;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.ApiResource;
import com.stripe.net.HttpClient;
import com.stripe.net.HttpURLConnectionClient;
import com.stripe.net.StripeRequest;
import com.stripe.net.StripeResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * stripe-mock ({@code stripe/stripe-mock}), one per test JVM: Stripe's official mock, which validates every request
 * against Stripe's OpenAPI spec and answers with fixtures (it keeps no state, so a PaymentIntent it returns never
 * becomes {@code requires_capture}). {@link #client()} records what stripe-java sends so tests can check the
 * {@code Idempotency-Key} and {@code Stripe-Version} headers.
 */
public final class StripeMock {

    /** Obviously fake; stripe-mock accepts any {@code sk_test_} key. */
    public static final String SECRET_KEY = "sk_test_fake";

    private static final class Holder {
        static final GenericContainer<?> INSTANCE = start();

        private static GenericContainer<?> start() {
            var container = new GenericContainer<>("stripe/stripe-mock:v0.205.0")
                    .withExposedPorts(12111)
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));
            container.start();
            return container;
        }
    }

    private StripeMock() {}

    /** {@code http://host:port} for {@code STRIPE_API_BASE}. */
    public static String apiBase() {
        var c = Holder.INSTANCE;
        return "http://%s:%d".formatted(c.getHost(), c.getMappedPort(12111));
    }

    /** One request stripe-java sent. */
    public record Sent(ApiResource.RequestMethod method, String path, List<String> idempotencyKeys, String version) {

        public boolean mutating() {
            return method == ApiResource.RequestMethod.POST || method == ApiResource.RequestMethod.DELETE;
        }
    }

    /** Wraps stripe-java's default transport and keeps every request. */
    public static final class Recorder extends HttpClient {
        private final HttpClient delegate = new HttpURLConnectionClient();
        private final List<Sent> sent = new CopyOnWriteArrayList<>();

        @Override
        public StripeResponse request(StripeRequest request) throws StripeException {
            sent.add(new Sent(
                    request.method(),
                    request.url().getPath(),
                    request.headers().allValues("Idempotency-Key"),
                    request.headers().firstValue("Stripe-Version").orElse("")));
            return delegate.request(request);
        }

        public List<Sent> sent() {
            return List.copyOf(sent);
        }

        public void clear() {
            sent.clear();
        }
    }

    /** A client for stripe-mock through {@link StripeClients} (so the pinned version is what is sent). */
    public static StripeClient client(Recorder recorder) {
        return StripeClients.create(SECRET_KEY, apiBase(), recorder);
    }
}
