package ca.northline.messaging.api;

import java.time.LocalTime;
import java.util.Optional;

/** A customer's quiet hours, for the account menu ("Quiet 10 pm–7 am"; S-59). Empty when they turned them off. */
public interface QuietHours {

    record Window(LocalTime from, LocalTime to) {}

    Optional<Window> of(String userId);
}
