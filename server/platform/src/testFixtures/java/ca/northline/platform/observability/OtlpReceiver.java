package ca.northline.platform.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * S-111 test fixture: a stand-in OpenTelemetry Collector. Accepts OTLP/HTTP protobuf on {@code /v1/traces},
 * {@code /v1/metrics} and {@code /v1/logs} (what Spring Boot's exporters send) and keeps what arrived, decoded, so a
 * test can assert that an app really exports — and what: service name, trace ids, span kinds, log bodies.
 *
 * <pre>{@code
 * static final OtlpReceiver OTLP = OtlpReceiver.start();
 * @DynamicPropertySource static void otlp(DynamicPropertyRegistry r) { OTLP.register(r::add); }
 * }</pre>
 */
public final class OtlpReceiver implements AutoCloseable {

    private final HttpServer server;
    private final List<ExportedSpan> spans = new CopyOnWriteArrayList<>();
    private final List<ExportedMetric> metrics = new CopyOnWriteArrayList<>();
    private final List<ExportedLog> logs = new CopyOnWriteArrayList<>();

    private OtlpReceiver(HttpServer server) {
        this.server = server;
    }

    public static OtlpReceiver start() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            var receiver = new OtlpReceiver(server);
            server.createContext("/v1/traces", receiver::traces);
            server.createContext("/v1/metrics", receiver::metrics);
            server.createContext("/v1/logs", receiver::logs);
            server.start();
            return receiver;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code http://127.0.0.1:<port>} — the value of {@code OTEL_EXPORTER_OTLP_ENDPOINT}. */
    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The properties that switch every export on and point it here (a {@code DynamicPropertyRegistry#add}). */
    public void register(PropertySink sink) {
        sink.add("management.tracing.export.enabled", () -> "true");
        sink.add("management.otlp.metrics.export.enabled", () -> "true");
        sink.add("management.logging.export.enabled", () -> "true");
        sink.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> endpoint() + "/v1/traces");
        sink.add("management.opentelemetry.logging.export.otlp.endpoint", () -> endpoint() + "/v1/logs");
        sink.add("management.otlp.metrics.export.url", () -> endpoint() + "/v1/metrics");
        sink.add("management.otlp.metrics.export.step", () -> "1s");
        sink.add("management.opentelemetry.tracing.export.schedule-delay", () -> "100ms");
        sink.add("management.opentelemetry.logging.export.schedule-delay", () -> "100ms");
        sink.add("management.tracing.sampling.probability", () -> "1.0");
    }

    /** {@code DynamicPropertyRegistry::add} without a Spring test dependency here. */
    @FunctionalInterface
    public interface PropertySink {
        void add(String name, java.util.function.Supplier<Object> value);
    }

    public List<ExportedSpan> spans() {
        return List.copyOf(spans);
    }

    public List<ExportedLog> logs() {
        return List.copyOf(logs);
    }

    public List<ExportedMetric> metrics() {
        return List.copyOf(metrics);
    }

    /** Waits up to {@code timeout} for spans matching {@code filter}; returns them (or fails with what did arrive). */
    public List<ExportedSpan> awaitSpans(Predicate<ExportedSpan> filter, Duration timeout) {
        return await(spans, filter, timeout, "span");
    }

    public List<ExportedLog> awaitLogs(Predicate<ExportedLog> filter, Duration timeout) {
        return await(logs, filter, timeout, "log record");
    }

    public List<ExportedMetric> awaitMetrics(Predicate<ExportedMetric> filter, Duration timeout) {
        return await(metrics, filter, timeout, "metric");
    }

    private static <T> List<T> await(List<T> source, Predicate<T> filter, Duration timeout, String what) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            var found = source.stream().filter(filter).toList();
            if (!found.isEmpty()) {
                return found;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("No matching " + what + " exported within " + timeout + "; received "
                        + source.size() + ": " + source.stream().limit(40).toList());
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    public void clear() {
        spans.clear();
        metrics.clear();
        logs.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void traces(HttpExchange exchange) throws IOException {
        var request = ExportTraceServiceRequest.parseFrom(body(exchange));
        for (var resource : request.getResourceSpansList()) {
            var service = service(resource.getResource());
            for (var scope : resource.getScopeSpansList()) {
                for (var span : scope.getSpansList()) {
                    spans.add(new ExportedSpan(
                            service,
                            span.getName(),
                            hex(span.getTraceId().toByteArray()),
                            hex(span.getSpanId().toByteArray()),
                            hex(span.getParentSpanId().toByteArray()),
                            span.getKind().name().replace("SPAN_KIND_", ""),
                            attributes(span.getAttributesList())));
                }
            }
        }
        ok(exchange);
    }

    private void metrics(HttpExchange exchange) throws IOException {
        var request = ExportMetricsServiceRequest.parseFrom(body(exchange));
        for (var resource : request.getResourceMetricsList()) {
            var service = service(resource.getResource());
            for (var scope : resource.getScopeMetricsList()) {
                for (var metric : scope.getMetricsList()) {
                    metrics.add(new ExportedMetric(service, metric.getName(), metric.getUnit()));
                }
            }
        }
        ok(exchange);
    }

    private void logs(HttpExchange exchange) throws IOException {
        var request = ExportLogsServiceRequest.parseFrom(body(exchange));
        for (var resource : request.getResourceLogsList()) {
            var service = service(resource.getResource());
            for (var scope : resource.getScopeLogsList()) {
                for (var log : scope.getLogRecordsList()) {
                    logs.add(new ExportedLog(
                            service,
                            log.getSeverityText(),
                            text(log.getBody()),
                            hex(log.getTraceId().toByteArray()),
                            hex(log.getSpanId().toByteArray()),
                            attributes(log.getAttributesList())));
                }
            }
        }
        ok(exchange);
    }

    private static byte[] body(HttpExchange exchange) throws IOException {
        var gzip = "gzip".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Encoding"));
        try (var in = gzip ? new GZIPInputStream(exchange.getRequestBody()) : exchange.getRequestBody()) {
            return in.readAllBytes();
        }
    }

    private static void ok(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private static String service(Resource resource) {
        return attributes(resource.getAttributesList()).getOrDefault("service.name", "");
    }

    private static Map<String, String> attributes(List<KeyValue> values) {
        return values.stream()
                .collect(Collectors.toUnmodifiableMap(KeyValue::getKey, kv -> text(kv.getValue()), (a, _) -> a));
    }

    private static String text(AnyValue value) {
        return switch (value.getValueCase()) {
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> Boolean.toString(value.getBoolValue());
            case INT_VALUE -> Long.toString(value.getIntValue());
            case DOUBLE_VALUE -> Double.toString(value.getDoubleValue());
            default -> value.toString().strip();
        };
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** One exported span. {@code traceId}/{@code spanId} are lower-case hex, {@code kind} SERVER, CLIENT, PRODUCER… */
    public record ExportedSpan(
            String service,
            String name,
            String traceId,
            String spanId,
            String parentSpanId,
            String kind,
            Map<String, String> attributes) {}

    public record ExportedMetric(String service, String name, String unit) {}

    public record ExportedLog(
            String service,
            String severity,
            String body,
            String traceId,
            String spanId,
            Map<String, String> attributes) {}
}
