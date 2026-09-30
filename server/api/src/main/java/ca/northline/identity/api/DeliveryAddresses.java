package ca.northline.identity.api;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A person's delivery addresses ({@code identity.addresses}), for checkout (S-51). The account's address book screen
 * is S-59's; checkout only reads the list and saves the address a person pays with. Never in event payloads.
 */
public interface DeliveryAddresses {

    /** The person's addresses, default first, then newest. */
    List<Address> of(String userId);

    Optional<Address> find(String userId, String addressId);

    /** Saves the address (or returns the same one saved before: street, unit and postal code match). */
    Address save(String userId, NewAddress address);

    /**
     * @param province two-letter code ({@code AB})
     * @param postal "T2R 0K3"
     * @param note access instructions shown to the courier ("Buzz 0804 · leave at door")
     */
    record Address(
            String id,
            String street,
            @Nullable String unit,
            String city,
            String province,
            String postal,
            @Nullable String note,
            boolean isDefault) {}

    record NewAddress(
            String street,
            @Nullable String unit,
            String city,
            String province,
            String postal,
            @Nullable String note) {}
}
