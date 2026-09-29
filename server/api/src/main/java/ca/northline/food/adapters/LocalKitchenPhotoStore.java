package ca.northline.food.adapters;

import ca.northline.food.application.KitchenPhotoStore;
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
 * Local fake of the menu-photo store: files under {@code northline.kitchen.photo-dir} (default: a
 * {@code northline-kitchen-photos} folder in the temp directory). Profiles {@code local} and {@code test} only.
 */
@Slf4j
@Component
@Profile({"local", "test"})
class LocalKitchenPhotoStore implements KitchenPhotoStore {

    private final Path root;

    LocalKitchenPhotoStore(
            @Value("${northline.kitchen.photo-dir:${java.io.tmpdir}/northline-kitchen-photos}") Path root) {
        this.root = root.toAbsolutePath().normalize();
        log.info("Local kitchen photo storage at {}", this.root);
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
            throw new IllegalArgumentException("Photo key escapes the storage root: " + key);
        }
        return file;
    }
}
