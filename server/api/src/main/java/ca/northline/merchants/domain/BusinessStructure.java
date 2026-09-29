package ca.northline.merchants.domain;

import static ca.northline.merchants.domain.PrincipalRole.CHAIR;
import static ca.northline.merchants.domain.PrincipalRole.DIRECTOR;
import static ca.northline.merchants.domain.PrincipalRole.OFFICER;
import static ca.northline.merchants.domain.PrincipalRole.OWNER;
import static ca.northline.merchants.domain.PrincipalRole.PARTNER;
import static ca.northline.merchants.domain.PrincipalRole.PARTNER_SIGNING;
import static ca.northline.merchants.domain.PrincipalRole.PRESIDENT;
import static ca.northline.merchants.domain.PrincipalRole.SECRETARY;
import static ca.northline.merchants.domain.PrincipalRole.SHAREHOLDER;
import static ca.northline.merchants.domain.PrincipalRole.TREASURER;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.RuleViolation.Violation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code merchants.merchants.structure} with the {@code x-principals} rules of docs/spec/legal-details.schema.json
 * (min count, allowed roles, KYC threshold, the one role that must be present). {@code BusinessStructureSpecTest}
 * checks this enum against the schema file.
 */
public enum BusinessStructure implements CodedEnum {
    SOLE(1, Set.of(OWNER), 0, null, false),
    PARTNERSHIP(2, Set.of(PARTNER_SIGNING, PARTNER), 25, PARTNER_SIGNING, true),
    CORP_AB(1, Set.of(DIRECTOR, OFFICER, SHAREHOLDER), 25, DIRECTOR, true),
    CORP_FED(1, Set.of(DIRECTOR, OFFICER, SHAREHOLDER), 25, DIRECTOR, true),
    CORP_EX(1, Set.of(DIRECTOR, OFFICER, SHAREHOLDER), 25, DIRECTOR, true),
    COOP(3, Set.of(CHAIR, DIRECTOR, SECRETARY, TREASURER), 0, CHAIR, false),
    NONPROFIT(3, Set.of(PRESIDENT, DIRECTOR, TREASURER, SECRETARY), 0, PRESIDENT, false);

    public static final String FIELD = "principals";
    public static final String NAME_REQUIRED = "Enter the full legal name.";
    public static final String PCT_RANGE = "Enter a percentage from 0 to 100.";
    public static final String ROLE_NOT_ALLOWED = "Pick one of the roles listed.";
    public static final String SUM_OVER_100 = "Ownership can't add up to more than 100 %.";

    private final int minPrincipals;

    @SuppressWarnings("ImmutableEnumChecker") // Set.of is unmodifiable
    private final Set<PrincipalRole> roles;

    private final int kycThresholdPct;
    private final @Nullable PrincipalRole requiredRole;
    private final boolean hasOwnership;

    BusinessStructure(
            int minPrincipals,
            Set<PrincipalRole> roles,
            int kycThresholdPct,
            @Nullable PrincipalRole requiredRole,
            boolean hasOwnership) {
        this.minPrincipals = minPrincipals;
        this.roles = roles;
        this.kycThresholdPct = kycThresholdPct;
        this.requiredRole = requiredRole;
        this.hasOwnership = hasOwnership;
    }

    public int minPrincipals() {
        return minPrincipals;
    }

    public Set<PrincipalRole> roles() {
        return roles;
    }

    public int kycThresholdPct() {
        return kycThresholdPct;
    }

    public @Nullable PrincipalRole requiredRole() {
        return requiredRole;
    }

    /** GST/HST is optional for sole proprietors and partnerships under $30k (validation-rules.md). */
    public boolean gstOptional() {
        return this == SOLE || this == PARTNERSHIP;
    }

    /** Sole proprietors have no principals table: the owner comes from {@code owner_legal_name}. */
    public boolean listsPrincipals() {
        return this != SOLE;
    }

    /** Checks the principals list against the structure's rules; returns one violation per offending field. */
    public List<Violation> check(List<Principal> principals) {
        var out = new ArrayList<Violation>();
        for (int i = 0; i < principals.size(); i++) {
            var p = principals.get(i);
            var at = FIELD + "[" + i + "]";
            if (p.legalName().isEmpty()) {
                out.add(new Violation(at + ".legalName", "required", NAME_REQUIRED));
            }
            if (!roles.contains(p.role())) {
                out.add(new Violation(at + ".role", "role", ROLE_NOT_ALLOWED));
            }
            var pct = p.ownershipPct();
            if (pct != null && (pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0)) {
                out.add(new Violation(at + ".ownershipPct", "range", PCT_RANGE));
            }
        }
        if (principals.size() < minPrincipals) {
            out.add(new Violation(FIELD, "min", minMessage()));
        } else if (requiredRole != null && principals.stream().noneMatch(p -> p.role() == requiredRole)) {
            out.add(new Violation(FIELD, "required_role", requiredRoleMessage()));
        } else if (hasOwnership) {
            var sum = principals.stream()
                    .map(Principal::ownershipPct)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(BigDecimal.valueOf(100)) > 0) {
                out.add(new Violation(FIELD, "sum", SUM_OVER_100));
            }
        }
        return out;
    }

    public String minMessage() {
        return switch (this) {
            case SOLE -> "Add the owner.";
            case PARTNERSHIP -> "Add at least 2 partners.";
            case CORP_AB, CORP_FED, CORP_EX -> "Add at least one director or owner.";
            case COOP, NONPROFIT -> "Add at least 3 board members.";
        };
    }

    public String requiredRoleMessage() {
        return switch (this) {
            case PARTNERSHIP -> "One partner must be the signing partner.";
            case CORP_AB, CORP_FED, CORP_EX -> "Add at least one director.";
            case COOP -> "Add the board chair.";
            case NONPROFIT -> "Add the president.";
            case SOLE -> "Add the owner.";
        };
    }
}
