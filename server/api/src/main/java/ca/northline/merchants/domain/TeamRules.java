package ca.northline.merchants.domain;

import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.MerchantRole;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Settings › Team &amp; roles rules. Roles come from the V018 CHECK (owner, technician, bookkeeper, cook); which ones a
 * business can hand out depends on its type (cooks in kitchens, technicians elsewhere). A business always keeps at least
 * one owner. Messages beyond validation-rules.md are recorded in docs/DECISIONS.md.
 */
public final class TeamRules {
    private TeamRules() {}

    public static final Duration INVITATION_TTL = Duration.ofDays(7);

    /** validation-rules.md › Registration (same patterns as the auth server). */
    public static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    public static final Pattern PHONE = Pattern.compile("^\\+?1?[\\s.-]?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}$");

    public static final String CONTACT_REQUIRED = "Enter an email or a mobile number.";
    public static final String EMAIL_FORMAT = "That doesn't look like an email address.";
    public static final String PHONE_FORMAT = "Enter a valid Canadian mobile, e.g. +1 403 555 0148.";
    public static final String ROLE_REQUIRED = "Choose a role.";
    public static final String ROLE_NOT_OFFERED = "This role isn't available for this business.";
    public static final String ALREADY_INVITED = "An invitation is already pending for this contact.";
    public static final String ALREADY_MEMBER = "This person is already on your team.";

    /** Roles an owner can give in a business of this type (owner first). */
    public static List<MerchantRole> rolesFor(MerchantType type) {
        return type == MerchantType.KITCHEN
                ? List.of(MerchantRole.OWNER, MerchantRole.COOK, MerchantRole.BOOKKEEPER)
                : List.of(MerchantRole.OWNER, MerchantRole.TECHNICIAN, MerchantRole.BOOKKEEPER);
    }

    public static MerchantRole role(@Nullable String code, MerchantType type) {
        if (code == null || code.isBlank()) {
            throw RuleViolation.of("role", "required", ROLE_REQUIRED);
        }
        var role = rolesFor(type).stream()
                .filter(r -> r.code().equals(code.strip().toLowerCase(Locale.ROOT)))
                .findFirst();
        return role.orElseThrow(() -> RuleViolation.of("role", "allowed", ROLE_NOT_OFFERED));
    }

    /** The contact an invitation goes to: exactly one of email (lower-cased) or phone (E.164). */
    public record Contact(@Nullable String email, @Nullable String phone) {

        public static Contact parse(@Nullable String rawEmail, @Nullable String rawPhone) {
            var email = rawEmail == null ? "" : rawEmail.strip();
            var phone = rawPhone == null ? "" : rawPhone.strip();
            if (!email.isEmpty()) {
                if (!EMAIL.matcher(email).matches()) {
                    throw RuleViolation.of("email", "format", EMAIL_FORMAT);
                }
                return new Contact(email.toLowerCase(Locale.ROOT), null);
            }
            if (!phone.isEmpty()) {
                if (!PHONE.matcher(phone).matches()) {
                    throw RuleViolation.of("phone", "format", PHONE_FORMAT);
                }
                return new Contact(null, e164(phone));
            }
            throw RuleViolation.of("email", "required", CONTACT_REQUIRED);
        }

        /** Whether a signed-in account is the person this was sent to. */
        public boolean matches(@Nullable String accountEmail, @Nullable String accountPhone) {
            if (email != null) {
                return accountEmail != null && email.equalsIgnoreCase(accountEmail.strip());
            }
            return phone != null && accountPhone != null && phone.equals(e164(accountPhone));
        }

        public String channel() {
            return email != null ? "email" : "sms";
        }
    }

    /** "+1 403 555 0148" / "(403) 555-0148" → "+14035550148". */
    public static String e164(String phone) {
        var digits = phone.replaceAll("\\D", "");
        return "+" + (digits.length() == 10 ? "1" + digits : digits);
    }

    /** Refuses to leave a business without an owner (demoting or removing the last one). */
    public static void keepAnOwner(long ownersLeft) {
        if (ownersLeft < 1) {
            throw new Conflict("last_owner", "A business needs at least one owner.");
        }
    }
}
