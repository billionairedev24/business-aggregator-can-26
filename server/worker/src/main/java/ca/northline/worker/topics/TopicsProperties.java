package ca.northline.worker.topics;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Topic settings the environment decides; the catalogue itself is the same everywhere.
 *
 * @param catalogue a catalogue file instead of the packaged {@code classpath:kafka/topics.yaml}
 * @param replicationFactor for new topics; blank = the broker's {@code default.replication.factor}
 * @param minInsyncReplicas set on every topic when given (prod: 2 with replication 3)
 */
@ConfigurationProperties("northline.topics")
public record TopicsProperties(
        @Nullable String catalogue,
        @Nullable Short replicationFactor,
        @Nullable Integer minInsyncReplicas) {

    Optional<Path> catalogueFile() {
        return Optional.ofNullable(catalogue).filter(c -> !c.isBlank()).map(Path::of);
    }

    Optional<Short> replication() {
        return Optional.ofNullable(replicationFactor);
    }

    Map<String, String> extraConfigs() {
        return minInsyncReplicas == null ? Map.of() : Map.of("min.insync.replicas", minInsyncReplicas.toString());
    }
}
