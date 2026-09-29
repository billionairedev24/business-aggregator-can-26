package ca.northline.messaging.adapters;

import ca.northline.messaging.application.AttachmentStorage;
import ca.northline.shared.storage.UsesLocalStorage;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Placeholder outside {@code local}/{@code test} while {@code STORAGE_PROVIDER=local} ({@link ObjectStoreAttachmentStorage}
 * otherwise): the
 * application starts, and uploading fails loudly.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
@UsesLocalStorage
class MessagingUnconfiguredAdapters {

    @Bean
    AttachmentStorage unconfiguredMessageAttachmentStorage() {
        return new AttachmentStorage() {
            @Override
            public void put(String key, byte[] bytes, String contentType) {
                throw new IllegalStateException("No attachment store configured (set STORAGE_PROVIDER, S-10)");
            }

            @Override
            public Optional<byte[]> get(String key) {
                return Optional.empty();
            }
        };
    }
}
