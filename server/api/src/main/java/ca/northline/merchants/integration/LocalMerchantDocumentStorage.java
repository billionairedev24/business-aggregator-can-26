package ca.northline.merchants.integration;

import ca.northline.merchants.application.DocumentStorage;
import ca.northline.shared.storage.UsesLocalStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * LOCAL / TEST ONLY. Keeps uploads on local disk under {@code northline.documents.local-dir} (default: a
 * {@code northline-documents} folder in the temp directory). Other providers: {@link ObjectStoreDocumentStorage}.
 */
@Component
@Profile({"local", "test"})
@UsesLocalStorage
@EnableConfigurationProperties(LocalMerchantDocumentStorage.Properties.class)
class LocalMerchantDocumentStorage implements DocumentStorage {

    @ConfigurationProperties("northline.documents")
    record Properties(@Nullable Path localDir) {}

    private final Path root;

    LocalMerchantDocumentStorage(Properties properties) {
        var dir = properties.localDir();
        this.root = (dir != null ? dir : Path.of(System.getProperty("java.io.tmpdir"), "northline-documents"))
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public String put(String merchantId, String documentId, String contentType, byte[] bytes) {
        var key = merchantId + "/" + documentId;
        try {
            var file = resolve(key);
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
            return key;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public byte[] get(String storageKey) {
        try {
            return Files.readAllBytes(resolve(storageKey));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path resolve(String key) {
        var file = root.resolve(key).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Bad storage key");
        }
        return file;
    }
}
