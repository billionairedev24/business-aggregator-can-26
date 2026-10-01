package ca.northline.account.application;

import ca.northline.account.application.ViewActivity.Item;
import ca.northline.account.domain.ActivityStatus;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * "Your week" is written by the server in the reader's language (the S-46 contract). English is design 06
 * ({@code upcoming}: "Grocery run · 3 shops", "Tonight 6–9 pm · pooled", "Thu 9:00 · $93.45 in escrow"); French
 * follows design/i18n-fr.js. Times are shown in the default market's zone (region configuration, never a zone in code).
 */
record UpcomingCopy(boolean fr, Locale locale, ZoneId zone) {

    static UpcomingCopy of(Locale locale, ZoneId zone) {
        var fr = "fr".equals(locale.getLanguage());
        return new UpcomingCopy(fr, fr ? Locale.CANADA_FRENCH : Locale.CANADA, zone);
    }

    private static final Map<ActivityStatus, String[]> STATES = Map.ofEntries(
            Map.entry(ActivityStatus.PACKING, new String[] {"Packing", "Emballage"}),
            Map.entry(ActivityStatus.READY, new String[] {"Ready", "Prête"}),
            Map.entry(ActivityStatus.ON_THE_WAY, new String[] {"On the way", "En route"}),
            Map.entry(ActivityStatus.DELIVERED, new String[] {"Delivered", "Livrée"}),
            Map.entry(ActivityStatus.PAID, new String[] {"Paid", "Payée"}),
            Map.entry(ActivityStatus.COOKING, new String[] {"Cooking", "En cuisine"}),
            Map.entry(ActivityStatus.REQUESTED, new String[] {"Requested", "Demandée"}),
            Map.entry(ActivityStatus.BOOKED, new String[] {"Booked", "Réservé"}),
            Map.entry(ActivityStatus.ESCROW, new String[] {"Booked", "Réservé"}),
            Map.entry(ActivityStatus.DEPOSIT_HELD, new String[] {"Deposit held", "Acompte retenu"}),
            Map.entry(ActivityStatus.ON_SITE, new String[] {"On site", "Sur place"}),
            Map.entry(ActivityStatus.COMPLETED, new String[] {"Completed", "Terminé"}),
            Map.entry(ActivityStatus.DONE, new String[] {"Done", "Terminé"}),
            Map.entry(ActivityStatus.WAITING, new String[] {"Waiting for quotes", "En attente de devis"}),
            Map.entry(ActivityStatus.QUOTE_READY, new String[] {"Quote ready", "Devis prêt"}),
            Map.entry(ActivityStatus.DECLINED, new String[] {"Declined", "Refusé"}),
            Map.entry(ActivityStatus.EXPIRED, new String[] {"Expired", "Expiré"}),
            Map.entry(ActivityStatus.REFUNDED, new String[] {"Refunded", "Remboursée"}),
            Map.entry(ActivityStatus.CANCELLED, new String[] {"Cancelled", "Annulée"}),
            Map.entry(ActivityStatus.CASE, new String[] {"Case", "Dossier"}));

    private String t(String en, String frText) {
        return fr ? frText : en;
    }

    String state(Item i) {
        if (i.status() == ActivityStatus.CASE && i.caseRef() != null) {
            return t("Case ", "Dossier ") + i.caseRef().number();
        }
        return STATES.getOrDefault(
                        i.status(), new String[] {i.status().code(), i.status().code()})[fr ? 1 : 0];
    }

    String title(Item i) {
        var with = String.join(", ", i.with());
        return switch (i.kind()) {
            case ORDER ->
                "pooled".equals(i.delivery())
                        ? t("Grocery run · ", "Tournée d’épicerie · ") + shops(i.shops())
                        : t("Delivery · ", "Livraison · ") + shops(i.shops());
            case FOOD -> with.isEmpty() ? t("Food order", "Commande de repas") : with;
            case BOOKING -> with.isEmpty() ? i.title() : i.title() + " · " + with;
            case QUOTE -> i.title();
        };
    }

    @Nullable
    String subtitle(Item i, Instant now) {
        var when = when(i.when(), i.whenEnd(), now);
        return switch (i.kind()) {
            case ORDER -> "pooled".equals(i.delivery()) ? when + t(" · pooled", " · groupée") : when;
            case FOOD -> "pickup".equals(i.delivery()) ? when + t(" · pickup", " · à emporter") : when;
            case BOOKING ->
                i.status() == ActivityStatus.ESCROW || i.status() == ActivityStatus.DEPOSIT_HELD
                        ? when + " · " + money(i.amountCents()) + t(" in escrow", " en fiducie")
                        : when;
            case QUOTE ->
                i.status() == ActivityStatus.QUOTE_READY
                        ? quotes(i.items()) + t(" from ", " à partir de ") + money(i.amountCents())
                        : t("Asked ", "Demandé à ")
                                + i.shops()
                                + (fr ? " pros" : i.shops() == 1 ? " provider" : " providers");
        };
    }

    private String shops(int n) {
        return n + (fr ? (n == 1 ? " commerce" : " commerces") : (n == 1 ? " shop" : " shops"));
    }

    private String quotes(int n) {
        return n + (fr ? " devis" : (n == 1 ? " quote" : " quotes"));
    }

    String money(long cents) {
        var f = NumberFormat.getCurrencyInstance(locale);
        f.setCurrency(Currency.getInstance("CAD"));
        return f.format(cents / 100.0);
    }

    /** "Tonight 6–9 pm", "Thu 9:00 am", "Sat Oct 12". */
    String when(Instant at, @Nullable Instant end, Instant now) {
        var local = at.atZone(zone);
        var today = now.atZone(zone).toLocalDate();
        var day = local.toLocalDate();
        var time = DateTimeFormatter.ofPattern(fr ? "H 'h' mm" : "h:mm a", locale);
        var range = end == null ? time.format(local) : time.format(local) + "–" + time.format(end.atZone(zone));
        if (day.equals(today)) {
            return (local.getHour() >= 17 ? t("Tonight ", "Ce soir ") : t("Today ", "Aujourd’hui ")) + range;
        }
        if (day.equals(today.plusDays(1))) {
            return t("Tomorrow ", "Demain ") + range;
        }
        if (!day.isAfter(today.plusDays(6)) && !day.isBefore(today)) {
            return DateTimeFormatter.ofPattern("EEE", locale).format(local) + " " + range;
        }
        return date(day);
    }

    private String date(LocalDate day) {
        return DateTimeFormatter.ofPattern(fr ? "d MMM" : "MMM d", locale).format(day);
    }
}
