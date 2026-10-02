package ca.northline.catalogue.persistence;

import ca.northline.shared.crypto.SealedColumn;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Where this module keeps sealed secrets (S-115): the key re-wrap job re-wraps them after a key rotation. */
@Configuration(proxyBeanMethods = false)
class IntegrationSealedColumns {

    @Bean
    SealedColumn commerceCredentialSealedColumn() {
        return new SealedColumn("catalogue.integrations", "id", "token_ref", "credentials_key", "credentials_enc", "");
    }
}
