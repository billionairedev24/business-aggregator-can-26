/**
 * Caller identity and merchant-scoped authorization, usable from every module's web adapter:
 * {@link ca.northline.shared.security.CurrentUser}, {@link ca.northline.shared.security.CurrentMember},
 * {@link ca.northline.shared.security.RequiresMerchant}, {@link ca.northline.shared.security.MerchantAccess}.
 */
@NamedInterface("security")
@NullMarked
package ca.northline.shared.security;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
