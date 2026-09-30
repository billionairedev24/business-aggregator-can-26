package ca.northline.merchants.integration;

import ca.northline.merchants.application.BusinessRegistry;
import ca.northline.merchants.domain.RegistryCheck.Answer;
import ca.northline.merchants.domain.RegistryQuery;
import ca.northline.merchants.domain.RegistryRecord;
import ca.northline.merchants.domain.RegistrySource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.annotation.GetExchange;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Corporations Canada — ISED's Federal Corporation API (GC API Store, subscription key in a header;
 * docs/runbooks/registries.md). {@code GET {base}/corporations/{corporationNumber}.json?lang=eng} returns the
 * corporation's record: its current name, status and business numbers. The record shape is read tolerantly (a JSON
 * array or object; names under {@code corporationNames[].CorporationName} or {@code name}) because it could not be
 * checked against a live subscription. 404 or an empty answer = not found.
 */
@Slf4j
class CorporationsCanadaRegistry implements BusinessRegistry {

    interface Api {
        @GetExchange("/corporations/{number}.json")
        String corporation(@PathVariable String number, @RequestParam("lang") String lang);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Api api;
    private final String publicPage;

    CorporationsCanadaRegistry(Api api, String publicPage) {
        this.api = api;
        this.publicPage = publicPage;
    }

    @Override
    public RegistrySource source() {
        return RegistrySource.CORPORATIONS_CANADA;
    }

    @Override
    public Answer lookup(RegistryQuery query) {
        var number = query.number().replaceAll("\\D", "");
        if (number.isEmpty()) {
            return new Answer.NotFound(null);
        }
        try {
            return parse(api.corporation(number, "eng"), number, publicPage + number);
        } catch (HttpClientErrorException.NotFound _) {
            return new Answer.NotFound(publicPage + number);
        } catch (RestClientException e) {
            log.warn("Corporations Canada lookup of {} failed: {}", number, e.getMessage());
            return new Answer.Unavailable("corporations_canada: " + e.getClass().getSimpleName());
        }
    }

    static Answer parse(@Nullable String body, String number, String reference) {
        if (body == null || body.isBlank()) {
            return new Answer.NotFound(reference);
        }
        var root = JSON.readTree(body);
        var node = root.isArray() ? (root.isEmpty() ? null : root.get(0)) : root;
        if (node == null || node.isMissingNode() || node.isNull() || node.isEmpty()) {
            return new Answer.NotFound(reference);
        }
        var name = currentName(node);
        if (name == null) {
            return new Answer.NotFound(reference);
        }
        var status = text(node.path("status"));
        var id = text(node.path("corporationId"));
        return new Answer.Found(
                new RegistryRecord(name, id == null ? number : id, RegistryStandings.of(status), status, null),
                reference);
    }

    private static @Nullable String currentName(JsonNode node) {
        String first = null;
        for (var entry : node.path("corporationNames")) {
            var n = entry.has("CorporationName") ? entry.get("CorporationName") : entry;
            var name = text(n.path("name"));
            if (name == null) {
                continue;
            }
            if (n.path("current").asBoolean(false)) {
                return name;
            }
            first = first == null ? name : first;
        }
        if (first != null) {
            return first;
        }
        var direct = text(node.path("corporationName"));
        return direct != null ? direct : text(node.path("name"));
    }

    /** A string, or an object's {@code text} / {@code desc} / {@code name}. */
    private static @Nullable String text(JsonNode n) {
        if (n.isString()) {
            return n.asString().isBlank() ? null : n.asString().strip();
        }
        if (n.isObject()) {
            for (var key : new String[] {"text", "desc", "description", "name", "value"}) {
                var t = text(n.path(key));
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }
}
