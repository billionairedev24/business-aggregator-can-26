package ca.northline.identity.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a business owner may see about their own team members (Settings › Team &amp; roles): name, contact and which
 * second factor they use. Added by the settings &amp; compliance workstream; never use it for customers.
 */
public interface TeamAccounts {

    /** Unknown ids are absent from the map. */
    Map<String, Account> accounts(Collection<String> userIds);

    default Optional<Account> account(String userId) {
        return Optional.ofNullable(accounts(List.of(userId)).get(userId));
    }

    /**
     * @param mfaPrimary {@code passkey} | {@code totp} | {@code sms}, or null when none was set up
     */
    record Account(
            String id,
            String displayName,
            @Nullable String email,
            @Nullable String phone,
            @Nullable String mfaPrimary) {}
}
