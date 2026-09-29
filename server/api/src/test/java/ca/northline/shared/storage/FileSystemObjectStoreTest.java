package ca.northline.shared.storage;

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

/** {@code STORAGE_PROVIDER=local}: the folder implementation. */
class FileSystemObjectStoreTest extends ObjectStoreContract {

    @TempDir
    static Path dir;

    @Override
    ObjectStore provider() {
        return new FileSystemObjectStore(dir);
    }
}
