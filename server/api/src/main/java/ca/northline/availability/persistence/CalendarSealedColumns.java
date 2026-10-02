package ca.northline.availability.persistence;

import ca.northline.shared.crypto.SealedColumn;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Where this module keeps sealed secrets (S-115): the key re-wrap job re-wraps them after a key rotation. */
@Configuration(proxyBeanMethods = false)
class CalendarSealedColumns {

    @Bean
    SealedColumn calendarRefreshTokenSealedColumn() {
        return new SealedColumn(
                "availability.calendar_links", "id", "token_ref", "refresh_token_key", "refresh_token_enc", "");
    }
}
