package ca.northline.food.persistence;

import ca.northline.shared.crypto.SealedColumn;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Where this module keeps sealed secrets (S-115): the key re-wrap job re-wraps them after a key rotation. */
@Configuration(proxyBeanMethods = false)
class PosSealedColumns {

    @Bean
    SealedColumn posCredentials() {
        return new SealedColumn("food.pos_connections", "id", "token_ref", "credentials_key", "credentials_enc", "");
    }
}
