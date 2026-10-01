package ca.northline.catalogue.adapters;

import ca.northline.catalogue.application.MediaStorage;
import ca.northline.shared.storage.UsesLocalStorage;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Placeholder outside {@code local}/{@code test} while {@code STORAGE_PROVIDER=local}: the application starts, and
 * uploading fails loudly. (The commerce integrations moved to {@code adapters.commerce}, S-35.)
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
class UnconfiguredAdapters {

    /** Only while {@code STORAGE_PROVIDER=local}; s3 | gcs | azure use {@link ObjectStoreMediaStorage}. */
    @Bean
    @UsesLocalStorage
    MediaStorage unconfiguredMediaStorage() {
        return new MediaStorage() {
            @Override
            public void put(String key, byte[] bytes, String contentType) {
                throw new IllegalStateException("No media store configured (set STORAGE_PROVIDER, S-10)");
            }

            @Override
            public Optional<byte[]> get(String key) {
                return Optional.empty();
            }

            @Override
            public void delete(String key) {
                // nothing was ever stored
            }
        };
    }
}
