package ca.northline.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * A mailbox: address plus optional display name.
 *
 * @param address e.g. {@code sam@example.com}
 * @param name e.g. {@code Sam Lee}; null = none
 */
public record EmailAddress(String address, @Nullable String name) {

    public EmailAddress {
        address = address.strip();
        if (address.isEmpty() || address.indexOf('@') < 1 || address.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Not an email address");
        }
        name = name == null || name.isBlank() ? null : name.strip();
    }

    public static EmailAddress of(String address) {
        return new EmailAddress(address, null);
    }

    /** Parses {@code Name <address>} or a bare address (the {@code EMAIL_FROM} format). */
    public static EmailAddress parse(String mailbox) {
        try {
            var parsed = new InternetAddress(mailbox.strip(), true);
            return new EmailAddress(parsed.getAddress(), parsed.getPersonal());
        } catch (AddressException e) {
            throw new IllegalArgumentException("Not a mailbox: " + mailbox, e);
        }
    }

    /** The domain part, lower case ({@code northline.ca}). */
    public String domain() {
        return address.substring(address.lastIndexOf('@') + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** RFC 5322 form, the name encoded when it isn't ASCII. */
    public InternetAddress toInternetAddress() {
        try {
            return new InternetAddress(address, name, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code Name <address>} (unencoded — for APIs that take the display form). */
    @Override
    public String toString() {
        return name == null ? address : name + " <" + address + ">";
    }
}
