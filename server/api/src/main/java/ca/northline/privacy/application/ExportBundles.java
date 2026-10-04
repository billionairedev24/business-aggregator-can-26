package ca.northline.privacy.application;

import ca.northline.privacy.application.PrivacyRequestStore.Request;
import ca.northline.privacy.domain.PrivacyRules;
import ca.northline.region.api.PrivacyRegime;
import ca.northline.region.api.PrivacyRegimes;
import ca.northline.shared.crypto.SecretSealer;
import ca.northline.shared.crypto.SecretSealer.Sealed;
import ca.northline.shared.privacy.PersonalDataContributor.Retention;
import ca.northline.shared.privacy.PersonalDataContributor.Section;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The access export: one machine-readable JSON document (every module's sections, as stored) and a readable summary
 * in the person's language, sealed together (envelope encryption, bound to the request id) before they reach object
 * storage.
 */
@Component
@RequiredArgsConstructor
class ExportBundles {

    static final String FORMAT = "northline.privacy-export/v1";

    private static final Map<Retention, List<String>> KEPT = Map.of(
            Retention.TAX_RECORDS,
            List.of(
                    "Sales and tax records (six years, as the tax acts require)",
                    "Registres de ventes et de taxes (six ans, comme l’exigent les lois fiscales)"),
            Retention.FINANCIAL_RECORDS,
            List.of("Payments, refunds and payouts", "Paiements, remboursements et versements"),
            Retention.CHARGEBACK_EVIDENCE,
            List.of(
                    "Evidence for card disputes and chargebacks",
                    "Preuves pour les litiges et rétrofacturations de carte"),
            Retention.BUSINESS_RECORDS,
            List.of(
                    "A business's own records of work done for it",
                    "Les registres d’une entreprise sur le travail fait pour elle"),
            Retention.KYC_RECORDS,
            List.of("Identity checks of business owners", "Vérifications d’identité des propriétaires d’entreprise"),
            Retention.AUDIT_LOG,
            List.of(
                    "The security audit log (ids only, seven years)",
                    "Le journal d’audit de sécurité (identifiants seulement, sept ans)"),
            Retention.CONSENT_PROOF,
            List.of(
                    "Proof of your consent to marketing messages and of its withdrawal (three years, as anti-spam law"
                            + " allows)",
                    "La preuve de votre consentement aux messages publicitaires et de son retrait (trois ans, comme le"
                            + " permet la loi anti-pourriel)"),
            Retention.AGE_CHECK_RECORDS,
            List.of(
                    "ID checks recorded when age-restricted items were handed over (two years; never the ID itself)",
                    "Vérifications d’identité inscrites à la remise d’articles soumis à un âge minimal (deux ans; jamais"
                            + " la pièce d’identité elle-même)"));

    private final SecretSealer sealer;
    private final JsonMapper json;
    private final PrivacyRegimes regimes;

    /** The sealed bundle, ready to store. */
    byte[] build(Request request, List<Section> sections, Locale locale, Instant generatedAt) {
        var regime = regimes.of(request.law(), request.province());
        var root = json.createObjectNode();
        root.put("format", FORMAT);
        root.put("reference", PrivacyRules.reference(request.number()));
        root.put("generatedAt", generatedAt.toString());
        root.put("subjectId", request.subjectId());
        var law = root.putObject("law");
        law.put("code", regime.law().code());
        law.put("name", regime.name(locale));
        law.put("authority", regime.authority(locale));
        law.put("authorityUrl", regime.authorityUrl());
        var data = root.putObject("sections");
        for (var section : sections) {
            data.set(section.key(), json.readTree(section.json()));
        }
        var bundle = json.createObjectNode();
        bundle.set("data", root);
        bundle.put("summary", summary(request, sections, regime, locale, generatedAt));
        var sealed = sealer.seal(json.writeValueAsString(bundle), context(request.id()));
        var stored = json.createObjectNode();
        stored.put("keyRef", sealed.keyRef());
        stored.put("wrappedKey", Base64.getEncoder().encodeToString(sealed.wrappedKey()));
        stored.put("ciphertext", Base64.getEncoder().encodeToString(sealed.ciphertext()));
        return json.writeValueAsString(stored).getBytes(StandardCharsets.UTF_8);
    }

    /** The data (pretty JSON) and the summary of a stored bundle. */
    Opened open(Request request, byte[] stored) {
        var node = json.readTree(stored);
        var sealed = new Sealed(
                node.get("keyRef").asString(),
                Base64.getDecoder().decode(node.get("wrappedKey").asString()),
                Base64.getDecoder().decode(node.get("ciphertext").asString()));
        var bundle = json.readTree(sealer.open(sealed, context(request.id())));
        return new Opened(
                json.writerWithDefaultPrettyPrinter().writeValueAsString(bundle.get("data")),
                bundle.get("summary").asString());
    }

    record Opened(String data, String summary) {}

    private static String summary(
            Request request, List<Section> sections, PrivacyRegime regime, Locale locale, Instant at) {
        var fr = locale.getLanguage().equals("fr");
        var date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                .withLocale(fr ? Locale.CANADA_FRENCH : Locale.CANADA)
                .withZone(ZoneOffset.UTC)
                .format(at);
        var out = new StringBuilder();
        out.append(fr ? "Northline — vos renseignements personnels" : "Northline — your personal information")
                .append("\n\n");
        out.append(fr ? "Demande " : "Request ")
                .append(PrivacyRules.reference(request.number()))
                .append(fr ? " · préparée le " : " · prepared on ")
                .append(date)
                .append(" (UTC)\n");
        out.append(fr ? "Loi applicable : " : "Law: ")
                .append(regime.name(locale))
                .append("\n\n");
        out.append(fr ? "Ce que nous détenons à votre sujet :" : "What we hold about you:")
                .append('\n');
        for (var section : sections) {
            if (section.records() > 0) {
                out.append("  - ")
                        .append(section.title(locale))
                        .append(fr ? " : " : ": ")
                        .append(section.records())
                        .append('\n');
            }
        }
        out.append('\n')
                .append(
                        fr
                                ? "Le fichier JSON joint contient chaque enregistrement tel que nous le conservons."
                                : "The JSON file holds every record as we keep it.")
                .append("\n\n");
        out.append(
                        fr
                                ? "Si vous demandez la suppression de votre compte, nous gardons seulement ceci, sans votre nom"
                                        + " ni vos coordonnées :"
                                : "If you ask us to delete your account, we keep only this, without your name or contact"
                                        + " details:")
                .append('\n');
        for (var reason : Retention.values()) {
            out.append("  - ")
                    .append(KEPT.getOrDefault(reason, List.of(reason.code(), reason.code()))
                            .get(fr ? 1 : 0))
                    .append('\n');
        }
        out.append('\n')
                .append(
                        fr
                                ? "Pour corriger un renseignement, utilisez « Demander une correction » dans votre compte. Si"
                                        + " notre réponse ne vous satisfait pas, vous pouvez vous adresser à : "
                                : "To correct something, use \"Ask for a correction\" in your account. If our answer doesn't"
                                        + " satisfy you, you can complain to: ")
                .append(regime.authority(locale))
                .append(" (")
                .append(regime.authorityUrl())
                .append(").\n");
        return out.toString();
    }

    private static String context(String requestId) {
        return "privacy-export:" + requestId;
    }
}
