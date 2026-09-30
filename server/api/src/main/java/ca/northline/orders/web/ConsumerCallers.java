package ca.northline.orders.web;

import ca.northline.orders.application.CartUseCases.CartOwner;
import ca.northline.orders.domain.CheckoutMessages;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentUser;
import ca.northline.shared.security.MerchantAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Who a consumer call is for. The cart paths are open to guests (SecurityConfig): a token makes it the person's cart;
 * without one, the consumer-bff's {@value #GUEST_HEADER} header keys a guest's cart. The guest id is a random key the
 * BFF sets (the browser's own header is dropped there) — never an identity, and stored only as its SHA-256.
 */
@Component
@RequiredArgsConstructor
class ConsumerCallers {

    static final String GUEST_HEADER = "X-Northline-Guest";
    private static final Pattern GUEST = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    private final MerchantAccess access;

    /** The signed-in person, if any; partner clients (no person) are refused. */
    Optional<CurrentUser> person() {
        if (access.isPartner()) {
            throw new AccessDeniedException("Partner clients have no cart.");
        }
        try {
            return Optional.of(access.currentUser());
        } catch (AuthenticationCredentialsNotFoundException e) {
            return Optional.empty();
        }
    }

    CartOwner owner(@Nullable String guestHeader) {
        return new CartOwner(person().map(CurrentUser::userId).orElse(null), guestKey(guestHeader));
    }

    static @Nullable String guestKey(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        if (!GUEST.matcher(header.strip()).matches()) {
            throw RuleViolation.of(GUEST_HEADER, "format", CheckoutMessages.GUEST);
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(header.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code lang=fr|en} wins over Accept-Language. */
    static String lang(@Nullable String lang, Locale locale) {
        if ("fr".equals(lang) || "en".equals(lang)) {
            return lang;
        }
        return "fr".equals(locale.getLanguage()) ? "fr" : "en";
    }
}
