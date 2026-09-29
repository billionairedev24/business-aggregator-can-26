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
 * folder in the temp directory). Profiles {@code local} and {@code test} only.
 */
@Slf4j
@Component
@Profile({"local", "test"})
@UsesLocalStorage
class LocalMediaStorage implements MediaStorage {

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
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
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
