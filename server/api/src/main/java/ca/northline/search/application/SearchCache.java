package ca.northline.search.application;

import java.time.Duration;
import java.util.Optional;

/** Outbound port: the hot-query cache (Redis/Valkey in the cloud, memory under local/test). Values are JSON. */
public interface SearchCache {

    Optional<String> get(String key);

    void put(String key, String json, Duration ttl);
}
