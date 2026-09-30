package ca.northline.merchants.integration;

import ca.northline.merchants.application.DocumentStorage;
import ca.northline.shared.storage.UsesLocalStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Placeholders outside {@code local}/{@code test} until the production adapters exist (bank linking is
 * {@link PaymentsBankLinking} since S-24, registries are {@link RegistriesConfig} since S-23, custom domains
 * {@link DomainsConfig} since S-31; object storage S-10 when {@code STORAGE_PROVIDER=local}): the application starts in the
 * {@code dev}/{@code staging}/{@code prod} profiles, and using the feature fails loudly — the same convention as the
 * other modules' unconfigured adapters.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
class UnconfiguredMerchantIntegrations {

    /** Only while {@code STORAGE_PROVIDER=local} (dev); s3 | gcs | azure use {@link ObjectStoreDocumentStorage}. */
    @Bean
    @UsesLocalStorage
    DocumentStorage unconfiguredDocumentStorage() {
        return new DocumentStorage() {
            @Override
            public String put(String merchantId, String documentId, String contentType, byte[] bytes) {
                throw unconfigured("document storage (set STORAGE_PROVIDER, S-10)");
            }

            @Override
            public byte[] get(String storageKey) {
                throw unconfigured("document storage (set STORAGE_PROVIDER, S-10)");
            }
        };
    }

    private static IllegalStateException unconfigured(String what) {
        return new IllegalStateException("No adapter configured for " + what);
    }
}
