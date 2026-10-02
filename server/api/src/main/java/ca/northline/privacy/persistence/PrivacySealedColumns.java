package ca.northline.privacy.persistence;

import ca.northline.privacy.application.PrivacyRequestStore;
import ca.northline.shared.crypto.SealedColumn;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where this module keeps sealed secrets (S-115): the key re-wrap job re-wraps them after a key rotation. The access
 * export bundles are sealed too but live in object storage for {@code PRIVACY_EXPORT_TTL} (7 days) only — they are not
 * re-wrapped; keep the old key enabled until the last one expired.
 */
@Configuration(proxyBeanMethods = false)
class PrivacySealedColumns {

    @Bean
    SealedColumn privacyRequestSealedColumn() {
        return new SealedColumn(
                "privacy.requests",
                "id",
                "sealed_key_ref",
                "sealed_key",
                "sealed_data",
                PrivacyRequestStore.SEALED_CONTEXT);
    }
}
