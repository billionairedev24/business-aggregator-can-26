package ca.northline.food.adapters;

import ca.northline.food.application.KitchenPhotoStore;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Placeholder outside {@code local}/{@code test} until the S3 photo store (ca-central-1) exists: the application
 * starts, and uploading a menu photo fails loudly.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
class KitchenUnconfiguredAdapters {

    @Bean
    KitchenPhotoStore unconfiguredKitchenPhotoStore() {
        return new KitchenPhotoStore() {
            @Override
            public void put(String key, byte[] bytes, String contentType) {
                throw new IllegalStateException("No kitchen photo store configured (S3 adapter pending)");
            }

            @Override
            public Optional<byte[]> get(String key) {
                return Optional.empty();
            }
        };
    }
}
