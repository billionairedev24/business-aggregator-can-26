package ca.northline.uat.application;

import java.util.Locale;
import java.util.Map;

/** The few words the CSV exports and the go/no-go reasons need, in English and French (the screens word the rest). */
final class Words {

    private static final Map<String, String[]> WORDS = Map.ofEntries(
            Map.entry("new", new String[] {"New", "Nouveau"}),
            Map.entry("triaged", new String[] {"Triaged", "Trié"}),
            Map.entry("accepted", new String[] {"Accepted", "Accepté"}),
            Map.entry("fixed", new String[] {"Fixed", "Corrigé"}),
            Map.entry("verified", new String[] {"Verified", "Vérifié"}),
            Map.entry("closed", new String[] {"Closed", "Fermé"}),
            Map.entry("wont_fix", new String[] {"Won't fix", "Ne sera pas corrigé"}),
            Map.entry("duplicate", new String[] {"Duplicate", "Doublon"}),
            Map.entry("bug", new String[] {"Bug", "Bogue"}),
            Map.entry("confusing", new String[] {"Confusing", "Déroutant"}),
            Map.entry("idea", new String[] {"Idea", "Idée"}),
            Map.entry("praise", new String[] {"Praise", "Bon point"}),
            Map.entry("blocker", new String[] {"Blocker", "Bloquant"}),
            Map.entry("major", new String[] {"Major", "Majeur"}),
            Map.entry("minor", new String[] {"Minor", "Mineur"}),
            Map.entry("cosmetic", new String[] {"Cosmetic", "Esthétique"}),
            Map.entry("provider", new String[] {"Service provider", "Prestataire"}),
            Map.entry("seller", new String[] {"Seller", "Vendeur"}),
            Map.entry("kitchen", new String[] {"Kitchen", "Cuisine"}),
            Map.entry("customer", new String[] {"Customer", "Client"}),
            Map.entry("courier", new String[] {"Courier", "Livreur"}),
            Map.entry("staff", new String[] {"Console staff", "Personnel de la console"}),
            Map.entry("yes", new String[] {"Yes", "Oui"}),
            Map.entry("no", new String[] {"No", "Non"}),
            Map.entry("go", new String[] {"Go", "Feu vert"}),
            Map.entry("no_go", new String[] {"No go", "Pas de feu vert"}),
            Map.entry("blocking_open", new String[] {
                "%d blocking item(s) not fixed yet.", "%d élément(s) bloquant(s) pas encore corrigé(s)."
            }),
            Map.entry("blocking_unverified", new String[] {
                "%d blocking fix(es) not verified yet.", "%d correctif(s) bloquant(s) pas encore vérifié(s)."
            }),
            Map.entry("blockers_untriaged", new String[] {
                "%d reported blocker(s) waiting for triage.", "%d signalement(s) bloquant(s) en attente de tri."
            }),
            Map.entry(
                    "no_participants",
                    new String[] {"%2$s: no pilot participant yet.", "%2$s : aucun participant au pilote."}),
            Map.entry(
                    "signoffs_pending",
                    new String[] {"%2$s: %1$d sign-off(s) still to come.", "%2$s : %1$d approbation(s) à venir."}),
            Map.entry("signoffs_blocked", new String[] {
                "%2$s: %1$d participant(s) refused to sign off.", "%2$s : %1$d participant(s) refuse(nt) d'approuver."
            }));

    private Words() {}

    static boolean french(Locale locale) {
        return "fr".equals(locale.getLanguage());
    }

    static String of(String code, Locale locale) {
        var pair = WORDS.get(code);
        return pair == null ? code : pair[french(locale) ? 1 : 0];
    }

    static String reason(String code, int count, String persona, Locale locale) {
        return of(code, locale).formatted(count, persona);
    }

    static String bool(boolean value, Locale locale) {
        return of(value ? "yes" : "no", locale);
    }
}
