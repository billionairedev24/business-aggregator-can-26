package ca.northline.catalogue.adapters;

import ca.northline.catalogue.application.CommerceSync;
import ca.northline.catalogue.application.MediaStorage;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.storage.UsesLocalStorage;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Placeholders outside {@code local}/{@code test} until the production adapters (object storage while
 * {@code STORAGE_PROVIDER=local}, Shopify / Square / Lightspeed OAuth apps) are built: the application starts, and using the feature fails loudly.
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
        };
    }

    @Bean
    CommerceSync unconfiguredCommerceSync() {
        return new CommerceSync() {
            @Override
            public String connect(String merchantId, CommerceProvider provider) {
                throw new IllegalStateException("No %s app configured".formatted(provider.code()));
            }

            @Override
            public List<ExternalItem> fetchInventory(String merchantId, CommerceProvider provider) {
                throw new IllegalStateException("No %s app configured".formatted(provider.code()));
            }
        };
    }
}
