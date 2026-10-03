package ca.northline.uat.adapters;

import ca.northline.shared.storage.UsesLocalStorage;
import ca.northline.uat.application.ScreenshotStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local fake of the UAT screenshot store: files under {@code northline.uat.screenshots-dir} (default: a
 * {@code northline-uat-screenshots} folder in the temp directory). Profiles {@code local} and {@code test} only.
 */
@Slf4j
@Component
@Profile({"local", "test"})
@UsesLocalStorage
class LocalScreenshotStorage implements ScreenshotStorage {

    private final Path root;

    LocalScreenshotStorage(
            @Value("${northline.uat.screenshots-dir:${java.io.tmpdir}/northline-uat-screenshots}") Path root) {
        this.root = root.toAbsolutePath().normalize();
        log.info("Local UAT screenshot storage at {}", this.root);
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

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public int deleteAll(String prefix) {
        var dir = resolve(prefix);
        if (!Files.isDirectory(dir) || dir.equals(root)) {
            return 0;
        }
        try (var files = Files.walk(dir)) {
            var all = files.sorted(Comparator.reverseOrder()).toList();
            var deleted = 0;
            for (var file : all) {
                if (Files.isRegularFile(file)) {
                    deleted++;
                }
                Files.deleteIfExists(file);
            }
            return deleted;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path resolve(String key) {
        var file = root.resolve(key).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Screenshot key escapes the storage root: " + key);
        }
        return file;
    }
}
