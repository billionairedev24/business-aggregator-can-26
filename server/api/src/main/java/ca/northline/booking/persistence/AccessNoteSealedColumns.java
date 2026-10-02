package ca.northline.booking.persistence;

import ca.northline.booking.application.CustomerBookingStore;
import ca.northline.shared.crypto.SealedColumn;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Where this module keeps sealed secrets (S-115): the key re-wrap job re-wraps them after a key rotation. */
@Configuration(proxyBeanMethods = false)
class AccessNoteSealedColumns {

    @Bean
    SealedColumn accessNotes() {
        return new SealedColumn(
                "booking.access_notes",
                "booking_id",
                "key_ref",
                "wrapped_key",
                "ciphertext",
                CustomerBookingStore.ACCESS_NOTE_CONTEXT);
    }
}
