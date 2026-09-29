package ca.northline.shared.storage;

import ca.northline.shared.RuleViolation;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * What every provider shares, in front of it: key validation, the presign TTL bound and the {@link VirusScanner} hook
 * on uploads. The {@link ObjectStore} bean is always one of these; closing it closes the provider's clients.
 */
@Slf4j
@RequiredArgsConstructor
final class GuardedObjectStore implements ObjectStore, AutoCloseable {

    static final String REJECTED = "This file can't be accepted. Try a different file.";

    private final ObjectStore provider;
    private final VirusScanner scanner;

    @Override
    public ObjectInfo put(String key, byte[] bytes, String contentType) {
        ObjectKeys.requireValid(key);
        switch (scanner.scan(key, contentType, bytes)) {
            case VirusScanner.Verdict.Clean _ -> {}
            case VirusScanner.Verdict.Infected(var threat) -> {
                log.warn(
                        "Upload {} ({} bytes, {}) rejected by the virus scanner: {}",
                        key,
                        bytes.length,
                        contentType,
                        threat);
                throw RuleViolation.of("file", "virus", REJECTED);
            }
        }
        return provider.put(key, bytes, contentType);
    }

    @Override
    public Optional<ObjectContent> get(String key) {
        return provider.get(ObjectKeys.requireValid(key));
    }

    @Override
    public Optional<ObjectInfo> info(String key) {
        return provider.info(ObjectKeys.requireValid(key));
    }

    @Override
    public void delete(String key) {
        provider.delete(ObjectKeys.requireValid(key));
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_PRESIGN_TTL) > 0) {
            throw new IllegalArgumentException(
                    "Presigned URL lifetime must be within (0, %s]: %s".formatted(MAX_PRESIGN_TTL, ttl));
        }
        return provider.presignGet(ObjectKeys.requireValid(key), ttl);
    }

    @Override
    public void close() throws Exception {
        if (provider instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
