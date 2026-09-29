package ca.northline.merchants.integration;

import ca.northline.merchants.application.DocumentStorage;
import ca.northline.merchants.application.VerificationGateways.BankLinking;
import ca.northline.merchants.application.VerificationGateways.DomainVerifier;
import ca.northline.merchants.application.VerificationGateways.IdentityVerification;
import ca.northline.merchants.application.VerificationGateways.Outcome;
import ca.northline.merchants.application.VerificationGateways.RegistryLookup;
import ca.northline.shared.storage.UsesLocalStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Placeholders outside {@code local}/{@code test} until the production adapters exist (Stripe Identity S-22, registry
 * lookups S-23, Financial Connections S-24, custom domains S-31; object storage S-10 when {@code STORAGE_PROVIDER=local}): the application starts in the
 * {@code dev}/{@code staging}/{@code prod} profiles, and using the feature fails loudly — the same convention as the
 * other modules' unconfigured adapters.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
class UnconfiguredMerchantIntegrations {

    @Bean
    IdentityVerification unconfiguredIdentityVerification() {
        return _ -> {
            throw unconfigured("identity verification (Stripe Identity, S-22)");
        };
    }

    @Bean
    RegistryLookup unconfiguredRegistryLookup() {
        return new RegistryLookup() {
            @Override
            public Outcome business(String legalName, String structure, String registryRef) {
                throw unconfigured("business registry lookups (S-23)");
            }

            @Override
            public Outcome licence(String registry, String number) {
                throw unconfigured("licence registry lookups (S-23)");
            }
        };
    }

    @Bean
    BankLinking unconfiguredBankLinking() {
        return _ -> {
            throw unconfigured("bank linking (Stripe Financial Connections, S-24)");
        };
    }

    @Bean
    DomainVerifier unconfiguredDomainVerifier() {
        return _ -> {
            throw unconfigured("custom domain verification (S-31)");
        };
    }

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
