package ca.northline.merchants.domain;

import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The verification checks of the onboarding wizard (design 02 {@code foodChecks}, {@code sellerChecks},
 * {@code genericChecks}), how the owner completes each one, and which {@code check_type} row it becomes.
 * {@link #LICENCE} is a family: one row per regulator of the selected categories ({@code licence:AMVIC}).
 */
public enum CheckKind {
    KYC("kyc", CheckType.KYC, null, Action.IDENTITY),
    REGISTRY("registry", CheckType.REGISTRY, null, Action.INSTANT),
    GST("gst", CheckType.REGISTRY, "CRA", Action.NUMBER),
    LICENCE("licence", CheckType.LICENCE, null, Action.NUMBER),
    AHS_PERMIT("ahs_permit", CheckType.AHS_PERMIT, "AHS", Action.NUMBER),
    FOOD_CERT("food_cert", CheckType.FOOD_CERT, null, Action.UPLOAD),
    INSPECTION("inspection", CheckType.INSPECTION, "AHS", Action.UPLOAD),
    INSURANCE("insurance", CheckType.INSURANCE, null, Action.UPLOAD),
    CATEGORY_PERMITS("category_permits", CheckType.LICENCE, null, Action.CHOOSE),
    PRODUCT_SAFETY("product_safety", CheckType.ATTESTATION, null, Action.SIGN),
    RETURNS_POLICY("returns_policy", CheckType.ATTESTATION, null, Action.CHOOSE),
    ALLERGEN_ATTESTATION("allergen_attestation", CheckType.ATTESTATION, null, Action.SIGN),
    AGLC("aglc", CheckType.LICENCE, "AGLC", Action.CHOOSE),
    BANK("bank", CheckType.BANK, null, Action.INSTANT),
    MFA("mfa", CheckType.MFA, null, Action.INSTANT),
    SITE_VISIT("site_visit", CheckType.SITE_VISIT, null, Action.SLOT);

    /** How the Verification step completes the check. */
    public enum Action implements ca.northline.shared.CodedEnum {
        /** One click through an external system (registry lookup, bank link, second factor). */
        INSTANT,
        /** Every owner who needs it verifies with Stripe Identity (S-22); the row follows their results. */
        IDENTITY,
        /** Enter a licence / permit / business number. */
        NUMBER,
        /** Upload a document. */
        UPLOAD,
        /** Read and sign an attestation. */
        SIGN,
        /** Pick an option (returns policy, "not applicable", "none required") or enter a number. */
        CHOOSE,
        /** Book a visit slot. */
        SLOT
    }

    public static final String LICENCE_PREFIX = "licence:";

    private final String key;
    private final CheckType type;
    private final @Nullable String registry;
    private final Action action;

    CheckKind(String key, CheckType type, @Nullable String registry, Action action) {
        this.key = key;
        this.type = type;
        this.registry = registry;
        this.action = action;
    }

    public String key() {
        return key;
    }

    public CheckType type() {
        return type;
    }

    public @Nullable String registry() {
        return registry;
    }

    public Action action() {
        return action;
    }

    public static Optional<CheckKind> ofKey(String key) {
        if (key.startsWith(LICENCE_PREFIX)) {
            return Optional.of(LICENCE);
        }
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }
}
