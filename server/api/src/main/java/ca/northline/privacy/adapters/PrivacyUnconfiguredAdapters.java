package ca.northline.privacy.adapters;

import ca.northline.privacy.application.ExportStorage;
import ca.northline.shared.storage.UsesLocalStorage;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Placeholder outside {@code local}/{@code test} while {@code STORAGE_PROVIDER=local} ({@link ObjectStoreExportStorage}
 * otherwise): the application starts, and preparing an export fails loudly (the request stays verified and is retried).
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
@UsesLocalStorage
class PrivacyUnconfiguredAdapters {

    @Bean
    ExportStorage unconfiguredPrivacyExportStorage() {
        return new ExportStorage() {
            @Override
            public void put(String key, byte[] bytes) {
                throw new IllegalStateException("No export store configured (set STORAGE_PROVIDER, S-10)");
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
