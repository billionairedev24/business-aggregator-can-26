package ca.northline.messaging.application;

import ca.northline.messaging.api.CaseReferences;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Messaging's own "Related to" contribution: the bookings, orders and disputes its recent threads are linked to
 * ("Booking BK-7712 · A. Osei", "Order NL-48213", "Dispute DS-1188"). Payouts and documents come from the modules that
 * own them.
 */
@Component
@RequiredArgsConstructor
class ThreadCaseReferences implements CaseReferences {

    private final ThreadStore threads;

    @Override
    public List<Reference> recent(String merchantId, Locale locale) {
        boolean fr = "fr".equals(locale.getLanguage());
        return threads.linkedRecords(merchantId, 10).stream()
                .map(r -> {
                    var code = Objects.requireNonNullElse(r.refCode(), r.refId());
                    var label = switch (r.refType()) {
                        case "booking" ->
                            "%s %s · %s".formatted(fr ? "Réservation" : "Booking", code, initial(r.counterpartName()));
                        case "order" -> "%s %s".formatted(fr ? "Commande" : "Order", code);
                        case "dispute" -> "%s %s".formatted(fr ? "Litige" : "Dispute", code);
                        default -> code;
                    };
                    return new Reference(r.refType(), r.refId(), label);
                })
                .toList();
    }

    /** "Amara Osei" → "A. Osei"; "M. Tran" stays. */
    static String initial(String name) {
        var parts = name.strip().split("\\s+", 2);
        if (parts.length < 2 || parts[0].endsWith(".")) {
            return name.strip();
        }
        return parts[0].charAt(0) + ". " + parts[1];
    }
}
