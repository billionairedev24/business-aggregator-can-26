package ca.northline.searchindex;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The parts of Meilisearch's REST API (v1, docs.meilisearch.com/reference/api) the listings read model uses, on
 * Spring's {@link RestClient} over the JDK HTTP client — no SDK. Bodies are JSON trees, written and read with this
 * module's own mapper, so the api and the worker agree on the document shape.
 *
 * <p>Writes are <b>tasks</b> in Meilisearch: a write returns a task uid at once and is applied later, in the order it
 * was enqueued per index. The writers {@link #await} their tasks, so a failure surfaces like a refused Elasticsearch
 * bulk item and a refresh reads what the previous one wrote.
 */
public final class MeilisearchClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Set<String> DONE = Set.of("succeeded", "failed", "canceled");

    private final RestClient http;
    private final Duration taskTimeout;

    private MeilisearchClient(RestClient http, Duration taskTimeout) {
        this.http = http;
        this.taskTimeout = taskTimeout;
    }

    public static MeilisearchClient create(MeilisearchConnection connection) {
        var jdk = HttpClient.newBuilder()
                .connectTimeout(connection.timeout())
                .proxy(ProxySelector.getDefault()) // https.proxyHost / http.nonProxyHosts, like every other client
                .build();
        var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(connection.timeout());
        var builder = RestClient.builder()
                .baseUrl(connection.url().toString())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(HttpStatusCode::isError, (_, response) -> {
                    var body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw error(response.getStatusCode().value(), body);
                });
        if (!connection.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + connection.apiKey());
        }
        return new MeilisearchClient(builder.build(), connection.taskTimeout());
    }

    // ── indexes ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** The index, or empty when Meilisearch has none by that uid. */
    public Optional<JsonNode> index(String uid) {
        try {
            return Optional.of(get("/indexes/{uid}", uid));
        } catch (MeilisearchException e) {
            if (e.getCode().equals("index_not_found")) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Every index uid (the listings indices are a handful). */
    public List<String> indexUids() {
        var out = new ArrayList<String>();
        var offset = 0;
        while (true) {
            var page = get("/indexes?limit=200&offset=" + offset);
            page.path("results").forEach(i -> out.add(i.path("uid").asString()));
            offset += 200;
            if (offset >= page.path("total").asInt(0)) {
                return List.copyOf(out);
            }
        }
    }

    public long createIndex(String uid, String primaryKey) {
        return task(post("/indexes", NODES.objectNode().put("uid", uid).put("primaryKey", primaryKey)));
    }

    public long deleteIndex(String uid) {
        return task(http.delete().uri("/indexes/{uid}", uid).retrieve().body(String.class));
    }

    /** Swaps the documents and settings of each pair of indexes, all pairs in one atomic task. */
    public long swap(Collection<List<String>> pairs) {
        var body = NODES.arrayNode();
        pairs.forEach(p -> body.addObject().set("indexes", strings(p)));
        return task(post("/swap-indexes", body));
    }

    public long documentCount(String uid) {
        return get("/indexes/{uid}/stats", uid).path("numberOfDocuments").asLong();
    }

    // ── settings ────────────────────────────────────────────────────────────────────────────────────────────────

    public JsonNode settings(String uid) {
        return get("/indexes/{uid}/settings", uid);
    }

    /** Changes the given settings only (the others stay); Meilisearch re-indexes by itself when it needs to. */
    public long updateSettings(String uid, ObjectNode settings) {
        return task(http.patch()
                .uri("/indexes/{uid}/settings", uid)
                .body(JSON.writeValueAsString(settings))
                .retrieve()
                .body(String.class));
    }

    // ── documents ───────────────────────────────────────────────────────────────────────────────────────────────

    /** Adds or <em>replaces</em> whole documents (by {@code id}). */
    public long addDocuments(String uid, ArrayNode documents) {
        return task(post("/indexes/{uid}/documents?primaryKey=id", documents, uid));
    }

    public long deleteDocuments(String uid, Collection<String> ids) {
        return task(post("/indexes/{uid}/documents/delete-batch", strings(ids), uid));
    }

    public Optional<JsonNode> document(String uid, String id) {
        try {
            return Optional.of(get("/indexes/{uid}/documents/{id}", uid, id));
        } catch (MeilisearchException e) {
            if (e.getCode().equals("document_not_found")) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** The ids of the documents matching a filter (filterable attributes only), at most {@code limit}. */
    public List<String> ids(String uid, String filter, int limit) {
        var body = NODES.objectNode().put("filter", filter).put("limit", limit);
        body.putArray("fields").add("id");
        var out = new ArrayList<String>();
        post("/indexes/{uid}/documents/fetch", body, uid)
                .path("results")
                .forEach(d -> out.add(d.path("id").asString()));
        return List.copyOf(out);
    }

    // ── search ──────────────────────────────────────────────────────────────────────────────────────────────────

    public JsonNode search(String uid, ObjectNode body) {
        return post("/indexes/{uid}/search", body, uid);
    }

    /** Several searches in one request (each query names its {@code indexUid}); the results in the same order. */
    public List<JsonNode> multiSearch(List<ObjectNode> queries) {
        var body = NODES.objectNode();
        var array = body.putArray("queries");
        queries.forEach(array::add);
        var out = new ArrayList<JsonNode>();
        post("/multi-search", body).path("results").forEach(out::add);
        return List.copyOf(out);
    }

    public boolean healthy() {
        return get("/health").path("status").asString("").equals("available");
    }

    // ── tasks ───────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Waits until every task has finished; returns them (with {@code details}: indexed and deleted counts).
     *
     * @throws MeilisearchException the first failed task's error, or {@code task_timeout} after the task timeout
     */
    public List<JsonNode> await(Collection<Long> taskUids) {
        if (taskUids.isEmpty()) {
            return List.of();
        }
        var uids = taskUids.stream().map(String::valueOf).collect(Collectors.joining(","));
        var deadline = System.nanoTime() + taskTimeout.toNanos();
        var pause = 10L;
        while (true) {
            var tasks = new ArrayList<JsonNode>();
            get("/tasks?uids=" + uids + "&limit=" + taskUids.size())
                    .path("results")
                    .forEach(tasks::add);
            if (tasks.size() >= taskUids.size()
                    && tasks.stream()
                            .allMatch(t -> DONE.contains(t.path("status").asString()))) {
                for (var task : tasks) {
                    if (!task.path("status").asString().equals("succeeded")) {
                        var error = task.path("error");
                        throw new MeilisearchException(
                                0,
                                error.path("code").asString(task.path("status").asString()),
                                task.path("type").asString() + " on "
                                        + task.path("indexUid").asString("-") + ": "
                                        + error.path("message").asString("canceled"));
                    }
                }
                return List.copyOf(tasks);
            }
            if (System.nanoTime() - deadline > 0) {
                throw new MeilisearchException(
                        0, "task_timeout", "tasks " + uids + " not finished after " + taskTimeout.toSeconds() + " s");
            }
            sleep(pause);
            pause = Math.min(pause * 2, 250);
        }
    }

    public List<JsonNode> await(long... taskUids) {
        return await(java.util.Arrays.stream(taskUids).boxed().toList());
    }

    /** Waits until the index has no enqueued or processing task (everything written so far is searchable). */
    public void awaitIdle(String uid) {
        var deadline = System.nanoTime() + taskTimeout.toNanos();
        while (get("/tasks?statuses=enqueued,processing&limit=1&indexUids={uid}", uid)
                        .path("total")
                        .asLong(0)
                > 0) {
            if (System.nanoTime() - deadline > 0) {
                throw new MeilisearchException(0, "task_timeout", uid + " still indexing after " + taskTimeout);
            }
            sleep(50);
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────────────────────────

    private JsonNode get(String uri, Object... variables) {
        return parse(http.get().uri(uri, variables).retrieve().body(String.class));
    }

    private JsonNode post(String uri, JsonNode body, Object... variables) {
        return parse(http.post()
                .uri(uri, variables)
                .body(JSON.writeValueAsString(body))
                .retrieve()
                .body(String.class));
    }

    private static long task(@Nullable String response) {
        return task(parse(response));
    }

    private static long task(JsonNode response) {
        var uid = response.path("taskUid");
        if (!uid.isNumber()) {
            throw new MeilisearchException(0, "no_task", "expected a task, got " + response);
        }
        return uid.asLong();
    }

    private static JsonNode parse(@Nullable String body) {
        return body == null || body.isBlank() ? NODES.objectNode() : JSON.readTree(body);
    }

    private static ArrayNode strings(Collection<String> values) {
        var array = NODES.arrayNode();
        values.forEach(array::add);
        return array;
    }

    private static MeilisearchException error(int status, String body) {
        try {
            var node = JSON.readTree(body);
            return new MeilisearchException(
                    status,
                    node.path("code").asString("http_" + status),
                    node.path("message").asString(body));
        } catch (RuntimeException e) {
            return new MeilisearchException(status, "http_" + status, body);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MeilisearchException(0, "interrupted", "waiting for Meilisearch");
        }
    }
}
