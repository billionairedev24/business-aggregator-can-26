package ca.northline.privacy.adapters;

import ca.northline.privacy.application.ExportStorage;
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
 * Local fake of the export store: files under {@code northline.privacy.exports-dir} (default: a {@code
 * northline-privacy} folder in the temp directory). Profiles {@code local} and {@code test} only. The files are the
 * sealed bundles, as in object storage.
 */
@Slf4j
@Component
@Profile({"local", "test"})
@UsesLocalStorage
class LocalExportStorage implements ExportStorage {

    private final Path root;

    LocalExportStorage(@Value("${northline.privacy.exports-dir:${java.io.tmpdir}/northline-privacy}") Path root) {
        this.root = root.toAbsolutePath().normalize();
        log.info("Local privacy export storage at {}", this.root);
    }

    @Override
    public void put(String key, byte[] bytes) {
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

    private Path resolve(String key) {
        var file = root.resolve(key).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Export key escapes the storage root: " + key);
        }
        return file;
    }
}
