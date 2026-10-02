package ca.northline.shared.storage;

import ca.northline.shared.Bytes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;

/**
 * {@code STORAGE_PROVIDER=local} under the {@code local}/{@code test} profiles: objects are files under a folder, the
 * content type in a {@code <file>.type} sidecar. The modules' own disk fakes keep serving their ports under {@code
 * local}; this is the reference implementation of the {@link ObjectStore} contract. A "presigned" URL is the {@code
 * file:} URI.
 */
final class FileSystemObjectStore implements ObjectStore {

    private static final String TYPE_SUFFIX = ".type";

    private final Path root;

    FileSystemObjectStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public ObjectInfo put(String key, byte[] bytes, String contentType) {
        var file = resolve(key);
        try {
            Files.createDirectories(file.getParent());
            var temp = Files.createTempFile(file.getParent(), ".upload", ".tmp");
            Files.write(temp, bytes);
            Files.writeString(typeFile(file), contentType, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new ObjectInfo(key, contentType, bytes.length);
    }

    @Override
    public Optional<ObjectContent> get(String key) {
        var file = resolve(key);
        try {
            var bytes = Files.readAllBytes(file);
            return Optional.of(
                    new ObjectContent(new ObjectInfo(key, contentType(file), bytes.length), Bytes.of(bytes)));
        } catch (NoSuchFileException _) {
            return Optional.empty();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public Optional<ObjectInfo> info(String key) {
        var file = resolve(key);
        try {
            return Files.isRegularFile(file)
                    ? Optional.of(new ObjectInfo(key, contentType(file), Files.size(file)))
                    : Optional.empty();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public void delete(String key) {
        var file = resolve(key);
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(typeFile(file));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public int deleteAll(String prefix) {
        var dir = resolve(prefix);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var files = Files.walk(dir)) {
            var all = files.sorted(Comparator.reverseOrder()).toList();
            var objects = 0;
            for (var file : all) {
                if (Files.isRegularFile(file) && !file.getFileName().toString().endsWith(TYPE_SUFFIX)) {
                    objects++;
                }
                Files.deleteIfExists(file);
            }
            return objects;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        return resolve(key).toUri();
    }

    private static String contentType(Path file) throws IOException {
        var type = typeFile(file);
        return Files.exists(type) ? Files.readString(type, StandardCharsets.UTF_8) : "application/octet-stream";
    }

    private static Path typeFile(Path file) {
        return file.resolveSibling(file.getFileName() + TYPE_SUFFIX);
    }

    private Path resolve(String key) {
        var file = root.resolve(ObjectKeys.requireValid(key)).normalize();
        if (!file.startsWith(root) || file.equals(root)) {
            throw new IllegalArgumentException("Object key escapes the storage root: " + key);
        }
        return file;
    }
}
