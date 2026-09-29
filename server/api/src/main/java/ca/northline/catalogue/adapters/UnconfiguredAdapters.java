package ca.northline.catalogue.adapters;

import ca.northline.catalogue.application.CommerceSync;
import ca.northline.catalogue.application.MediaStorage;
import ca.northline.catalogue.domain.CommerceProvider;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Placeholders outside {@code local}/{@code test} until the production adapters (S3 media store in ca-central-1,
 * Shopify / Square / Lightspeed OAuth apps) are built: the application starts, and using the feature fails loudly.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
class UnconfiguredAdapters {

    @Bean
    MediaStorage unconfiguredMediaStorage() {
        return new MediaStorage() {
            @Override
            public void put(String key, byte[] bytes, String contentType) {
                throw new IllegalStateException("No media store configured (S3 adapter pending)");
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
