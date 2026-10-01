package ca.northline.catalogue.adapters;

import ca.northline.catalogue.application.MediaStorage;
import ca.northline.shared.storage.UsesLocalStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local fake of the media store: files under {@code northline.media.local-dir} (default: a {@code northline-media}
 * folder in the temp directory). Profiles {@code local} and {@code test} only. Dev-seed listings (V104, V113, V184)
 * reference {@code seed/<name>.jpg}; until something is uploaded under that key, the bundled sample picture from
 * {@code seed-media/catalogue} is served (S-77, as {@code LocalKitchenPhotoStore} does for menu items).
 */
@Slf4j
@Component
@Profile({"local", "test"})
@UsesLocalStorage
class LocalMediaStorage implements MediaStorage {

    private static final String SEED_PREFIX = "seed/";
    private static final String SEED_MEDIA = "/seed-media/catalogue/";

    private final Path root;

    LocalMediaStorage(@Value("${northline.media.local-dir:${java.io.tmpdir}/northline-media}") Path root) {
        this.root = root.toAbsolutePath().normalize();
        log.info("Local media storage at {}", this.root);
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        var file = resolve(key);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        var file = resolve(key);
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : seedSample(key);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Optional<byte[]> seedSample(String key) throws IOException {
        var name = key.startsWith(SEED_PREFIX) ? key.substring(SEED_PREFIX.length()) : "";
        if (!name.matches("[a-z0-9-]+\\.jpg")) {
            return Optional.empty();
        }
        try (var in = LocalMediaStorage.class.getResourceAsStream(SEED_MEDIA + name)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        }
    }

    private Path resolve(String key) {
        var file = root.resolve(key).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Media key escapes the storage root: " + key);
        }
        return file;
    }
}
