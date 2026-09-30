package ca.northline.identity.api;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where and in which language to reach a user (S-13 notification emails; the S-27 notifications consumer reads the same
 * columns). Only active accounts: suspended or erased users are absent from the map, like unknown ids.
 */
public interface NotificationContacts {

    Map<String, Contact> contacts(Collection<String> userIds);

    default Optional<Contact> contact(String userId) {
        return Optional.ofNullable(contacts(List.of(userId)).get(userId));
    }

    /** @param locale the profile's {@code locale} (en-CA | fr-CA; en-CA when unset) */
    record Contact(
            String id,
            String displayName,
            @Nullable String email,
            @Nullable String phone,
            Locale locale) {}
}
