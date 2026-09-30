package ca.northline.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * S-30: opens a {@link RequiresMerchant} handler to partner clients (tokens with role {@code partner}, issued to
 * {@code partner:<name>} with {@code client_credentials}) — only for the businesses in the token's {@code merchants}
 * claim, and only when the token has {@link #value()} as scope. Handlers without it refuse partner tokens
 * ({@code 403 partner_not_allowed}). Keep it off handlers that take a {@link CurrentMember}: a partner is no team member.
 *
 * <pre>{@code
 * @GetMapping("/listings")
 * @RequiresMerchant(VIEW)
 * @PartnerAccess(PartnerAccess.READ)
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PartnerAccess {

    /** Reading the business's data. */
    String READ = "api.read";

    /** Changing it. */
    String WRITE = "api.write";

    /** The scope the partner's token must carry. */
    String value();
}
