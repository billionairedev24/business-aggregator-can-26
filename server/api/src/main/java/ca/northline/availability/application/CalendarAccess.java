package ca.northline.availability.application;

import ca.northline.availability.application.CalendarGateway.GrantRevoked;
import ca.northline.availability.application.CalendarGateway.Token;
import ca.northline.availability.application.CalendarGateway.Unauthorized;
import ca.northline.availability.application.CalendarLinkRepository.Link;
import ca.northline.shared.crypto.SecretSealer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Access tokens for the provider calls. The refresh token is opened (envelope decryption) only to refresh; access
 * tokens live in memory per api instance until a minute before they expire. A 401 refreshes once and retries; a
 * refused refresh ({@code invalid_grant}) or a second 401 puts the link in {@code reconnect}, which the Studio shows as
 * "Reconnect". Callers get an empty result then and stop — the marking is written in their transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class CalendarAccess {

    static final Duration EARLY = Duration.ofMinutes(1);

    private final CalendarGateways gateways;
    private final CalendarLinkRepository links;
    private final SecretSealer sealer;
    private final Clock clock;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    /** Runs {@code call} with an access token; empty when the link needs a reconnect (it is marked so). */
    <T> Optional<T> with(Link link, Function<String, T> call) {
        if (link.needsReconnect()) {
            return Optional.empty();
        }
        try {
            try {
                return Optional.of(call.apply(token(link, false)));
            } catch (Unauthorized _) {
                return Optional.of(call.apply(token(link, true)));
            }
        } catch (GrantRevoked | Unauthorized e) {
            tokens.remove(link.id());
            links.markReconnect(link.id(), reason(e), clock.instant());
            log.info(
                    "Calendar link {} ({}) needs a reconnect: {}",
                    link.id(),
                    link.provider().code(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    /** The access token the authorization code gave, so the first reads don't refresh at once. */
    void remember(String linkId, String accessToken, Instant expiresAt) {
        tokens.put(linkId, new Token(accessToken, null, expiresAt));
    }

    void forget(String linkId) {
        tokens.remove(linkId);
    }

    /** The refresh token in clear (disconnect revokes it), or null when none is stored. */
    @Nullable
    String refreshToken(Link link) {
        return links.refreshToken(link.id())
                .map(box -> sealer.open(box, link.id()))
                .orElse(null);
    }

    private String token(Link link, boolean force) {
        var cached = tokens.get(link.id());
        if (!force
                && cached != null
                && cached.expiresAt().isAfter(clock.instant().plus(EARLY))) {
            return cached.accessToken();
        }
        var refresh = refreshToken(link);
        if (refresh == null) {
            throw new GrantRevoked("no refresh token stored");
        }
        var token = gateways.get(link.provider()).refresh(refresh);
        if (token.refreshToken() != null && !token.refreshToken().equals(refresh)) {
            links.replaceRefreshToken(link.id(), sealer.seal(token.refreshToken(), link.id()));
        }
        tokens.put(link.id(), token);
        return token.accessToken();
    }

    private static String reason(RuntimeException e) {
        return e instanceof GrantRevoked ? "revoked" : "unauthorized";
    }
}
