package ca.northline.messaging.application;

import ca.northline.merchants.api.ComplianceDocuments;
import ca.northline.merchants.api.ComplianceDocuments.LedgerDocument;
import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.messaging.api.CaseReferences;
import ca.northline.region.api.Regions;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Compliance's "Related to" contribution (S-66): the documents on the business's compliance ledger, "Liability
 * insurance · exp. Jan 2027" / « Assurance responsabilité · exp. janv. 2027 ». The merchants module owns the ledger
 * ({@link ComplianceDocuments}); messaging names the rows here because merchants can't implement
 * {@link CaseReferences} itself (messaging depends on merchants — a module cycle). Names are generic, never a
 * province's agency: the row's registry and reference say which one.
 */
@Component
@RequiredArgsConstructor
class DocumentCaseReferences implements CaseReferences {

    private final ComplianceDocuments documents;
    private final MerchantDirectory merchants;
    private final Regions regions;

    @Override
    public List<Reference> recent(String merchantId, Locale locale) {
        var list = documents.documents(merchantId);
        if (list.isEmpty()) {
            return List.of();
        }
        boolean fr = "fr".equals(locale.getLanguage());
        var month = DateTimeFormatter.ofPattern("MMM yyyy", fr ? Locale.CANADA_FRENCH : Locale.CANADA)
                .withZone(zone(merchantId));
        return list.stream()
                .map(d -> {
                    var expires = d.expiresAt();
                    var label = expires == null
                            ? name(d, fr)
                            : "%s · exp. %s".formatted(name(d, fr), month.format(expires));
                    return new Reference("document", d.id(), label);
                })
                .toList();
    }

    /** The recorded label, else a generic name for the check type with its registry / reference. */
    static String name(LedgerDocument d, boolean fr) {
        var label = d.label();
        if (label != null && !label.isBlank()) {
            return label.strip();
        }
        var registry = d.registry();
        var reference = d.reference();
        return switch (d.checkType()) {
            case "licence" ->
                registry == null ? (fr ? "Permis" : "Licence") : fr ? "Permis " + registry : registry + " licence";
            case "insurance" -> fr ? "Assurance responsabilité" : "Liability insurance";
            case "wcb" -> fr ? "Attestation d’indemnisation des travailleurs" : "Workers’ compensation clearance";
            case "ahs_permit" ->
                (fr ? "Permis alimentaire" : "Food permit") + (reference == null ? "" : " · #" + reference);
            case "food_cert" -> fr ? "Certificats de manipulation des aliments" : "Food handler certificates";
            case "inspection" -> fr ? "Inspection de la cuisine" : "Kitchen inspection";
            case "attestation" -> "Attestation";
            case "registry" ->
                "CRA".equalsIgnoreCase(registry)
                        ? (fr ? "Inscription TPS/TVH" : "GST/HST registration")
                        : (fr ? "Inscription au registre" : "Registry record");
            default -> "Document";
        };
    }

    private ZoneId zone(String merchantId) {
        return merchants
                .profile(merchantId)
                .map(p -> regions.zone(p.province(), p.city()))
                .orElseGet(regions::platformZone);
    }
}
