package ca.northline.shared.security;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.FINANCE_READ;
import static ca.northline.shared.security.MerchantPermission.OPERATE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.shared.CodedEnum;
import java.util.EnumSet;
import java.util.Set;

/**
 * Team role of a user within one merchant ({@code merchants.merchant_members.role}, CHECK in V018). The permission
 * matrix follows the Studio design (Team screen "Can" column and the owner/technician/bookkeeper data-table rules).
 */
public enum MerchantRole implements CodedEnum {
    /** Everything incl. payouts, bank, team and deleting records. */
    OWNER(EnumSet.allOf(MerchantPermission.class)),
    /** Own jobs, messages, complete &amp; photo; edits but never deletes. */
    TECHNICIAN(EnumSet.of(VIEW, OPERATE, EDIT)),
    /** Kitchen staff: tickets/KDS, 86 items; edits but never deletes. */
    COOK(EnumSet.of(VIEW, OPERATE, EDIT)),
    /** Reports and payouts, read-only. */
    BOOKKEEPER(EnumSet.of(VIEW, FINANCE_READ));

    @SuppressWarnings("ImmutableEnumChecker") // Set.copyOf is unmodifiable
    private final Set<MerchantPermission> permissions;

    MerchantRole(Set<MerchantPermission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean grants(MerchantPermission permission) {
        return permissions.contains(permission);
    }
}
