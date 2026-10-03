package ca.northline.uat.adapters;

import ca.northline.shared.storage.UsesLocalStorage;
import ca.northline.uat.application.ScreenshotStorage;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Placeholder outside {@code local}/{@code test} while {@code STORAGE_PROVIDER=local} ({@link ObjectStoreScreenshotStorage}
 * otherwise): the
 * application starts, and uploading fails loudly.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
@UsesLocalStorage
class UatUnconfiguredAdapters {

    @Bean
    ScreenshotStorage unconfiguredScreenshotStorage() {
        return new ScreenshotStorage() {
            @Override
            public void put(String key, byte[] bytes, String contentType) {
                throw new IllegalStateException("No screenshot store configured (set STORAGE_PROVIDER, S-10)");
            }

            @Override
            public Optional<byte[]> get(String key) {
                return Optional.empty();
            }

            @Override
            public void delete(String key) {
                // nothing was ever stored
            }

            @Override
            public int deleteAll(String prefix) {
                return 0; // nothing was ever stored
            }
        };
    }
}
