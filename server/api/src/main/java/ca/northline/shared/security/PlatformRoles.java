package ca.northline.shared.security;

import java.util.List;

/**
 * Outbound port: a person's platform roles ({@code identity.platform_roles}: {@code staff}, {@link StaffRole} codes).
 * Implemented by the identity module; used by the local dev-auth filter to mint the {@code roles} claim northline-auth
 * would issue (S-90).
 */
public interface PlatformRoles {

    List<String> of(String userId);
}
