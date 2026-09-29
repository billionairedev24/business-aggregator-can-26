package ca.northline.shared.security;

/**
 * The caller acting inside one merchant, already authorized by {@link RequiresMerchant}. Declare it as a controller
 * parameter on a {@code /api/v1/merchants/{merchantId}/…} handler to get the caller's team role (e.g. technicians
 * only see their own jobs).
 */
public record CurrentMember(String merchantId, String userId, MerchantRole role) {
    public boolean can(MerchantPermission permission) {
        return role.grants(permission);
    }
}
