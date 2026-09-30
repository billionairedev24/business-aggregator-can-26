package ca.northline.search.application;

import ca.northline.search.domain.SearchQuery;
import ca.northline.search.domain.SuggestQuery;
import ca.northline.shared.CodedEnum;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Search and suggestions through the hot-query cache: the same question within {@code northline.search.cache-ttl}
 * (30 s) is answered from Redis/Valkey without touching Elasticsearch. The cache is best effort — when it is down the
 * index answers.
 */
@Slf4j
@Service
class SearchService implements SearchListings, SuggestListings {

    private static final TypeReference<List<Suggestion>> SUGGESTIONS = new TypeReference<>() {};

    private final SearchIndex index;
    private final SearchCache cache;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration ttl;

    SearchService(SearchIndex index, SearchCache cache, JsonMapper json, Clock clock, SearchSettings settings) {
        this.index = index;
        this.cache = cache;
        this.json = json;
        this.clock = clock;
        this.ttl = settings.cacheTtl();
    }

    @Override
    public SearchResults search(SearchQuery q) {
        var key = key(
                "q",
                q.text(),
                q.market(),
                q.language(),
                sorted(q.kinds()),
                q.categoryId(),
                q.minPriceCents(),
                q.maxPriceCents(),
                q.minRating(),
                sorted(q.tiers()),
                q.instantBook(),
                q.openNow(),
                q.deliveryTonight(),
                sorted(q.dietary()),
                sorted(q.allergenFree()),
                q.near() == null ? null : q.near().lat() + "," + q.near().lng(),
                q.radiusKm(),
                q.sort(),
                q.size(),
                q.after());
        return cached(key, SearchResults.class, () -> index.search(q, SearchMoment.now(clock)));
    }

    @Override
    public List<Suggestion> suggest(SuggestQuery q) {
        var key = key(
                "s",
                q.prefix().toLowerCase(java.util.Locale.ROOT),
                q.market(),
                q.language(),
                sorted(q.kinds()),
                q.size());
        var hit = read(key);
        if (hit != null) {
            try {
                return json.readValue(hit, SUGGESTIONS);
            } catch (JacksonException e) {
                log.debug("unreadable cached suggestions {}: {}", key, e.getMessage());
            }
        }
        var suggestions = index.suggest(q);
        write(key, suggestions);
        return suggestions;
    }

    private <T> T cached(String key, Class<T> type, Supplier<T> load) {
        var hit = read(key);
        if (hit != null) {
            try {
                return json.readValue(hit, type);
            } catch (JacksonException e) {
                log.debug("unreadable cached result {}: {}", key, e.getMessage());
            }
        }
        var value = load.get();
        write(key, value);
        return value;
    }

    private @org.jspecify.annotations.Nullable String read(String key) {
        try {
            return cache.get(key).orElse(null);
        } catch (RuntimeException e) {
            log.warn("search cache unavailable, answering from the index: {}", e.toString());
            return null;
        }
    }

    private void write(String key, Object value) {
        try {
            cache.put(key, json.writeValueAsString(value), ttl);
        } catch (RuntimeException e) {
            log.warn("search cache unavailable, not caching: {}", e.toString());
        }
    }

    private static String sorted(Collection<?> values) {
        return values.stream()
                .map(v -> v instanceof CodedEnum c ? c.code() : String.valueOf(v))
                .sorted()
                .toList()
                .toString();
    }

    /** {@code nl:search:<q|s>:<sha-256 of every parameter>}. */
    private static String key(String kind, @org.jspecify.annotations.Nullable Object... parts) {
        var canonical = new StringBuilder();
        for (var part : parts) {
            canonical
                    .append(part instanceof CodedEnum c ? c.code() : Objects.toString(part, "∅"))
                    .append('\u001f');
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "nl:search:" + kind + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
