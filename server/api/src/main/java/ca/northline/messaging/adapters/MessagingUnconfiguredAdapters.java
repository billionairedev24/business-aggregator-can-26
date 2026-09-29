package ca.northline.messaging.adapters;

import ca.northline.messaging.application.AttachmentStorage;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Placeholder outside {@code local}/{@code test} until the S3 attachment store (ca-central-1) is built: the
 * application starts, and uploading fails loudly.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
class MessagingUnconfiguredAdapters {

    @Bean
    AttachmentStorage unconfiguredMessageAttachmentStorage() {
        return new AttachmentStorage() {
            @Override
            public void put(String key, byte[] bytes, String contentType) {
                throw new IllegalStateException("No attachment store configured (S3 adapter pending)");
            }

            @Override
            public Optional<byte[]> get(String key) {
                return Optional.empty();
            }
        };
    }
}
