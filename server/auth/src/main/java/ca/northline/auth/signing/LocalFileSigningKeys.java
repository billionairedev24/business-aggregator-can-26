package ca.northline.auth.signing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * {@code local} provider: the key pairs live in one JWK set file ({@value #FILE}, mode 600) in a directory
 * ({@code SIGNING_KEYS_DIR}), created with a first key on first use. Every instance that mounts the directory signs
 * with the same key, and a restart keeps every token valid.
 *
 * <p>Rotation is time-based, so instances need no coordination: {@link #rotate} adds a key that becomes active
 * {@code publishAhead} later ({@code nbf}), and gives the current key an {@code exp} {@code retireAfter} after that.
 * Until then both are in the JWK set. Keys past their {@code exp} are dropped from the file at the next rotation. The
 * file is re-read when it changes, so a rotation done by the command or another instance is picked up without a
 * restart. Writes are atomic (temporary file + rename) under a lock file.
 */
@Slf4j
public final class LocalFileSigningKeys implements SigningKeys {

    static final String FILE = "signing-keys.jwks.json";
    private static final String LOCK = ".lock";
    private static final Duration RELOAD_AFTER = Duration.ofSeconds(30);
    /** One in-process lock per directory: {@link FileChannel#lock()} only excludes other processes. */
    private static final Map<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();

    private final Path dir;
    private final Path file;
    private final Clock clock;
    private final Duration publishAhead;
    private final Duration retireAfter;
    private volatile Snapshot snapshot;

    /** The file's keys as last read. */
    private record Snapshot(List<ECKey> keys, @Nullable Instant modified, Instant readAt) {}

    /** Result of a rotation: the new key and when it starts signing. */
    public record Rotation(String keyId, Instant activatesAt, List<KeyState> keys) {}

    public LocalFileSigningKeys(Path dir, Clock clock, Duration publishAhead, Duration retireAfter) {
        this.dir = dir.toAbsolutePath().normalize();
        this.file = this.dir.resolve(FILE);
        this.clock = clock;
        this.publishAhead = publishAhead;
        this.retireAfter = retireAfter;
        this.snapshot = locked(this::loadOrCreate);
    }

    public Path file() {
        return file;
    }

    @Override
    public SigningKey active() {
        return activeOf(current(), clock.instant())
                .map(LocalSigningKey::new)
                .orElseThrow(() -> new IllegalStateException("No usable signing key in " + file
                        + ": rotate with --immediately (docs/runbooks/key-rotation.md)"));
    }

    @Override
    public List<ECKey> published() {
        var now = clock.instant();
        return current().stream()
                .filter(k -> !expired(k, now))
                .sorted(Comparator.comparing(LocalFileSigningKeys::notBefore).reversed())
                .map(ECKey::toPublicJWK)
                .toList();
    }

    @Override
    public List<KeyState> describe() {
        return describe(current());
    }

    /**
     * Adds a key that signs from {@code now + publishAhead} (or at once when {@code immediately}); the keys that sign
     * until then are retired {@code retireAfter} after the switch. Safe to run from several instances or the command.
     */
    public Rotation rotate(boolean immediately) {
        return locked(() -> rotateLocked(immediately));
    }

    private Rotation rotateLocked(boolean immediately) {
        var now = clock.instant();
        var activatesAt = immediately ? now : now.plus(publishAhead);
        var retiresAt = activatesAt.plus(retireAfter);
        var keys = new ArrayList<ECKey>();
        for (var key : read()) {
            if (expired(key, now)) {
                continue; // clean-up: nobody can hold a valid token signed by it any more
            }
            keys.add(key.getExpirationTime() == null ? withExpiry(key, retiresAt) : key);
        }
        var fresh = generate(now, activatesAt);
        keys.add(fresh);
        write(keys);
        log.info(
                "Signing key rotation: new key {} signs from {}; previous keys retire at {} ({})",
                fresh.getKeyID(),
                activatesAt,
                retiresAt,
                file);
        return new Rotation(fresh.getKeyID(), activatesAt, describe(keys));
    }

    /** The rotation job: rotates when the newest key is at least {@code age} old (checked under the lock). */
    public Optional<Rotation> rotateIfOlderThan(Duration age) {
        return locked(() -> {
            var newest = read().stream()
                    .map(LocalFileSigningKeys::notBefore)
                    .max(Comparator.naturalOrder())
                    .orElse(Instant.EPOCH);
            return newest.plus(age).isAfter(clock.instant())
                    ? Optional.<Rotation>empty()
                    : Optional.of(rotateLocked(false));
        });
    }

    /** Removes a key at once (compromised key): tokens it signed stop verifying. Refused for the only usable key. */
    public List<KeyState> retire(String keyId) {
        return locked(() -> {
            var now = clock.instant();
            var keys = new ArrayList<>(read());
            if (!keys.removeIf(k -> k.getKeyID().equals(keyId))) {
                throw new IllegalArgumentException("No key " + keyId + " in " + file);
            }
            if (keys.stream().noneMatch(k -> !expired(k, now))) {
                throw new IllegalStateException("Refusing to remove the last key: rotate --immediately first");
            }
            write(keys);
            log.warn("Signing key {} removed from {}", keyId, file);
            return describe(keys);
        });
    }

    private List<KeyState> describe(List<ECKey> keys) {
        var now = clock.instant();
        var active = activeOf(keys, now).map(JWK::getKeyID).orElse("");
        return keys.stream()
                .filter(k -> !expired(k, now))
                .sorted(Comparator.comparing(LocalFileSigningKeys::notBefore))
                .map(k -> {
                    var exp = k.getExpirationTime();
                    var until = exp == null ? "" : ", published until " + exp.toInstant();
                    if (k.getKeyID().equals(active)) {
                        return new KeyState(k.getKeyID(), KeyState.Status.ACTIVE, "since " + notBefore(k) + until);
                    }
                    if (notBefore(k).isAfter(now)) {
                        return new KeyState(k.getKeyID(), KeyState.Status.NEXT, "signs from " + notBefore(k));
                    }
                    return new KeyState(k.getKeyID(), KeyState.Status.RETIRING, "verification only" + until);
                })
                .toList();
    }

    private List<ECKey> current() {
        var seen = snapshot;
        var now = clock.instant();
        if (Duration.between(seen.readAt(), now).compareTo(RELOAD_AFTER) < 0
                && java.util.Objects.equals(seen.modified(), modified())) {
            return seen.keys();
        }
        var fresh = new Snapshot(read(), modified(), now);
        snapshot = fresh;
        return fresh.keys();
    }

    private Snapshot loadOrCreate() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't create the signing key directory " + dir, e);
        }
        if (!Files.exists(file)) {
            var now = clock.instant();
            var first = generate(now, now);
            write(List.of(first));
            log.info("Signing keys: created {} with key {}", file, first.getKeyID());
        }
        var keys = read();
        if (keys.isEmpty()) {
            throw new IllegalStateException(file + " holds no EC P-256 key pair");
        }
        return new Snapshot(keys, modified(), clock.instant());
    }

    private List<ECKey> read() {
        try {
            var set = JWKSet.parse(Files.readString(file, StandardCharsets.UTF_8));
            return set.getKeys().stream()
                    .filter(ECKey.class::isInstance)
                    .map(ECKey.class::cast)
                    .filter(k -> Curve.P_256.equals(k.getCurve()) && k.isPrivate())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read " + file, e);
        } catch (ParseException e) {
            throw new IllegalStateException(file + " is not a JWK set: " + e.getMessage(), e);
        }
    }

    private void write(List<ECKey> keys) {
        try {
            var json = new JWKSet(List.<JWK>copyOf(keys)).toString(false);
            var tmp = Files.createTempFile(dir, ".signing-keys", ".tmp");
            restrict(tmp);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            snapshot = new Snapshot(List.copyOf(keys), modified(), clock.instant());
        } catch (IOException e) {
            throw new UncheckedIOException("Can't write " + file, e);
        }
    }

    private @Nullable Instant modified() {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException _) {
            return null;
        }
    }

    private <T> T locked(Supplier<T> action) {
        var lock = IN_PROCESS.computeIfAbsent(dir, _ -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(dir);
            try (var channel =
                            FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var _ = channel.lock()) {
                return action.get();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Can't lock " + dir, e);
        } finally {
            lock.unlock();
        }
    }

    private static void restrict(Path path) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static ECKey generate(Instant issuedAt, Instant activatesAt) {
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .issueTime(Date.from(issuedAt))
                    .notBeforeTime(Date.from(activatesAt))
                    .keyIDFromThumbprint(true)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("EC P-256 key generation failed", e);
        }
    }

    private static ECKey withExpiry(ECKey key, Instant exp) {
        return new ECKey.Builder(key).expirationTime(Date.from(exp)).build();
    }

    /** The usable key that became active last (on a tie, the one added last). */
    private static Optional<ECKey> activeOf(List<ECKey> keys, Instant now) {
        @Nullable ECKey active = null;
        for (var key : keys) {
            if (!notBefore(key).isAfter(now)
                    && !expired(key, now)
                    && (active == null || !notBefore(key).isBefore(notBefore(active)))) {
                active = key;
            }
        }
        return Optional.ofNullable(active);
    }

    private static Instant notBefore(ECKey key) {
        var nbf = key.getNotBeforeTime() != null ? key.getNotBeforeTime() : key.getIssueTime();
        return nbf == null ? Instant.EPOCH : nbf.toInstant();
    }

    private static boolean expired(ECKey key, Instant now) {
        var exp = key.getExpirationTime();
        return exp != null && !exp.toInstant().isAfter(now);
    }

    /** Signs in-process with the key pair from the file. */
    private record LocalSigningKey(ECKey key) implements SigningKey {

        @Override
        public String keyId() {
            return key.getKeyID();
        }

        @Override
        public byte[] sign(byte[] signingInput) {
            try {
                return new ECDSASigner(key)
                        .sign(new JWSHeader(JWSAlgorithm.ES256), signingInput)
                        .decode();
            } catch (JOSEException e) {
                throw new IllegalStateException("ES256 signing failed", e);
            }
        }
    }
}
