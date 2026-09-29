package ca.northline.food.adapters;

import ca.northline.food.application.KitchenPhotoStore;
import ca.northline.shared.storage.UsesLocalStorage;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Placeholder outside {@code local}/{@code test} while {@code STORAGE_PROVIDER=local} ({@link ObjectStoreKitchenPhotoStore} otherwise): the application
 * starts, and uploading a menu photo fails loudly.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
@UsesLocalStorage
class KitchenUnconfiguredAdapters {

    @Bean
    KitchenPhotoStore unconfiguredKitchenPhotoStore() {
        return new KitchenPhotoStore() {
            @Override
            public void put(String key, byte[] bytes, String contentType) {
                throw new IllegalStateException("No kitchen photo store configured (set STORAGE_PROVIDER, S-10)");
            }

            @Override
            public Optional<byte[]> get(String key) {
                return Optional.empty();
            }
        };
    }
}
