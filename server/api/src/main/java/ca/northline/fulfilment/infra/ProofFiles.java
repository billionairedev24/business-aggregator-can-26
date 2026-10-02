package ca.northline.fulfilment.infra;

import ca.northline.fulfilment.application.FulfilmentProperties;
import ca.northline.fulfilment.application.ProofStorage;
import ca.northline.shared.Bytes;
import ca.northline.shared.storage.UsesLocalStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Proofs of delivery on the local disk ({@code local} / {@code test}; {@code northline.fulfilment.proof-dir}, default
 * {@code $TMPDIR/northline-proofs}). Other providers: {@link ObjectStoreProofStorage}.
 */
@Component
@Profile({"local", "test"})
@UsesLocalStorage
class ProofFiles implements ProofStorage {

    private final Path root;

    ProofFiles(FulfilmentProperties props) {
        this.root = Objects.requireNonNullElseGet(
                        props.proofDir(), () -> Path.of(System.getProperty("java.io.tmpdir"), "northline-proofs"))
                .toAbsolutePath()
                .normalize();
    }

    private Path resolve(String key) {
        var path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Bad proof key " + key);
        }
        return path;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        try {
            var path = resolve(key);
            Files.createDirectories(Objects.requireNonNull(path.getParent()));
            Files.write(path, bytes);
            Files.writeString(path.resolveSibling(path.getFileName() + ".type"), contentType, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<StoredFile> get(String key) {
        var path = resolve(key);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            var typeFile = path.resolveSibling(path.getFileName() + ".type");
            var type = Files.exists(typeFile) ? Files.readString(typeFile) : "application/octet-stream";
            return Optional.of(new StoredFile(Bytes.of(Files.readAllBytes(path)), type));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        var path = resolve(key);
        try {
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".type"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
