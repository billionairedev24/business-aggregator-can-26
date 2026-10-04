package ca.northline.merchants.domain;

import java.time.LocalDate;
import java.util.List;

/** Rules and messages of restricted-class licences (fr-CA in docs/spec/validation-messages.fr-CA.tsv). */
public final class LicenceRules {

    private LicenceRules() {}

    public static final List<String> REJECT_REASONS =
            List.of("unreadable", "wrong_class", "wrong_business", "expired", "not_valid", "other");

    /** Days before expiry the owners are reminded. */
    public static final int REMIND_DAYS = 30;

    public static final String CLASS = "Choose alcohol, tobacco and vape, or cannabis accessories.";
    public static final String NUMBER = "Enter the licence or permit number, 2 to 40 characters.";
    public static final String EXPIRY = "Enter an expiry date in the future.";
    public static final String DOCUMENT = "Upload the licence as a PDF, PNG or JPEG under 10 MB.";
    public static final String DECISION = "Choose approve or reject.";
    public static final String REASON = "Choose why the licence is rejected.";
    public static final String NOTE = "At most 500 characters.";
    public static final String NO_PROVINCE = "Add your business's province before adding a licence.";
    public static final String NOT_PENDING = "This licence was already decided. Refresh to see it.";

    public static boolean validNumber(String number) {
        var n = number.strip();
        return n.length() >= 2 && n.length() <= 40;
    }

    public static boolean validExpiry(LocalDate expiresOn, LocalDate today) {
        return expiresOn.isAfter(today);
    }
}
