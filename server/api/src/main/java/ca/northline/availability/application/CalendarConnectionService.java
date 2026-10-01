package ca.northline.availability.application;

import ca.northline.availability.application.CalendarGateway.Authorization;
import ca.northline.availability.application.CalendarGateway.Grant;
import ca.northline.availability.application.CalendarLinkRepository.Link;
import ca.northline.availability.application.CalendarSyncEvents.CalendarConnected;
import ca.northline.availability.application.CalendarSyncRepository.OAuthRequest;
import ca.northline.availability.application.CalendarUseCases.ChooseCalendarSources;
import ca.northline.availability.application.CalendarUseCases.CompleteCalendarConnection;
import ca.northline.availability.application.CalendarUseCases.ListCalendarSources;
import ca.northline.availability.application.CalendarUseCases.SourceOption;
import ca.northline.availability.application.CalendarUseCases.SourcesView;
import ca.northline.availability.domain.CalendarLinkState;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.availability.domain.CalendarScope;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.crypto.SecretSealer;
import ca.northline.shared.security.MerchantAccess;
import ca.northline.shared.security.MerchantPermission;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Google / Outlook connections (S-32): OAuth 2.0 authorization code with PKCE (S256) and a single-use state bound to
 * the member who started it; minimal scopes first ({@link CalendarScope#EVENTS}), the calendar list only when the
 * member opens "Choose calendars" (incremental consent); the refresh token sealed with the envelope key; disconnect
 * stops notifications, removes the events Northline wrote and revokes the grant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CalendarConnectionService implements CompleteCalendarConnection, ListCalendarSources, ChooseCalendarSources {

    static final Duration REQUEST_TTL = Duration.ofMinutes(10);

    private final CalendarLinkRepository links;
    private final CalendarSyncRepository sync;
    private final CalendarGateways gateways;
    private final CalendarAccess access;
    private final SecretSealer sealer;
    private final CalendarSettings settings;
    private final MerchantAccess merchantAccess;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** Stores a PKCE request and returns the provider's consent page. */
    @Transactional
    URI start(String merchantId, String userId, CalendarProvider provider, Set<CalendarScope> scopes, boolean first) {
        var gateway = gateways.get(provider);
        var state = CalendarSecrets.random();
        var verifier = CalendarSecrets.random();
        var now = clock.instant();
        sync.saveRequest(new OAuthRequest(
                CalendarSecrets.sha256(state),
                merchantId,
                userId,
                provider,
                verifier,
                scopes,
                now,
                now.plus(REQUEST_TTL)));
        return gateway.authorizationUrl(new Authorization(
                state, CalendarSecrets.challenge(verifier), settings.redirectUri(provider.code()), scopes, first));
    }

    @Override
    @Transactional
    public Completion complete(Callback callback) {
        var provider = callback.provider();
        var request = callback.state() == null
                ? null
                : sync.takeRequest(CalendarSecrets.sha256(callback.state()), clock.instant())
                        .orElse(null);
        if (request == null) {
            return new Completion(null, provider, Outcome.EXPIRED, false);
        }
        var choose = request.scopes().contains(CalendarScope.CALENDAR_LIST);
        if (!request.memberUserId().equals(callback.userId()) || request.provider() != provider) {
            log.warn("Calendar callback for another member or provider refused (state of {})", request.memberUserId());
            return new Completion(null, provider, Outcome.FAILED, false);
        }
        merchantAccess.require(request.merchantId(), MerchantPermission.EDIT);
        if (callback.error() != null || callback.code() == null) {
            var denied = "access_denied".equals(callback.error());
            return new Completion(request.merchantId(), provider, denied ? Outcome.DENIED : Outcome.FAILED, choose);
        }
        var gateway = gateways.get(provider);
        Grant grant;
        try {
            grant = gateway.exchange(callback.code(), request.codeVerifier(), settings.redirectUri(provider.code()));
        } catch (RuntimeException e) {
            log.warn("Calendar code exchange with {} failed: {}", provider.code(), e.getMessage());
            return new Completion(request.merchantId(), provider, Outcome.FAILED, choose);
        }
        if (!grant.scopes().containsAll(request.scopes())) {
            // Google lets people untick permissions on the consent screen; without events there is nothing to sync.
            if (!grant.scopes().contains(CalendarScope.EVENTS) && grant.refreshToken() != null) {
                gateway.revoke(grant.refreshToken());
            }
            return new Completion(request.merchantId(), provider, Outcome.SCOPES, choose);
        }
        var link = save(request, grant);
        if (link == null) {
            return new Completion(request.merchantId(), provider, Outcome.FAILED, choose);
        }
        events.publishEvent(new CalendarConnected(link.id()));
        return new Completion(request.merchantId(), provider, Outcome.CONNECTED, choose);
    }

    private @org.jspecify.annotations.Nullable Link save(OAuthRequest request, Grant grant) {
        var gateway = gateways.get(request.provider());
        var existing = links.find(request.merchantId(), request.memberUserId(), request.provider())
                .orElse(null);
        var sameAccount = existing != null && grant.subject().equals(existing.externalAccountId());
        if (existing != null && !sameAccount) {
            // another account: the old one's channels, events and token go first
            disconnect(existing);
            existing = null;
        }
        var refresh = grant.refreshToken();
        if (refresh == null && existing != null) {
            refresh = access.refreshToken(existing);
        }
        if (refresh == null) {
            log.warn(
                    "{} returned no refresh token for member {}",
                    request.provider().code(),
                    request.memberUserId());
            return null;
        }
        var linkId = existing == null ? Ids.next() : existing.id();
        var scopes = EnumSet.noneOf(CalendarScope.class);
        scopes.addAll(grant.scopes());
        if (existing != null) {
            scopes.addAll(existing.scopes());
        }
        var write = gateway.writeCalendar(grant.accessToken());
        var now = clock.instant();
        var sealed = sealer.seal(refresh, linkId);
        var link = links.saveGrant(
                new Link(
                        linkId,
                        request.merchantId(),
                        request.memberUserId(),
                        request.provider(),
                        grant.accountLabel(),
                        sealed.keyRef(),
                        existing == null ? now : existing.connectedAt(),
                        existing == null ? null : existing.lastSyncAt(),
                        CalendarLinkState.CONNECTED,
                        scopes,
                        grant.subject(),
                        write.id()),
                sealed);
        access.remember(linkId, grant.accessToken(), grant.expiresAt());
        if (sync.sources(linkId).isEmpty()) {
            sync.replaceSources(linkId, java.util.Map.of(write.id(), write.name()));
        }
        return link;
    }

    /**
     * Stops notifications, deletes the upcoming events Northline wrote, revokes the grant (Google; Microsoft has no
     * per-app revocation endpoint — the token is destroyed and the member can remove the app in their account) and
     * deletes the link with its sources, busy blocks and mirrors.
     *
     * <p>S-136: the link is locked first ({@code FOR UPDATE}), so a read or write-back in flight finishes before this
     * sees the channels and mirrors, and none starts until the link is gone.
     */
    @Transactional
    void disconnect(Link stale) {
        var link = links.lockForDelete(stale.id()).orElse(null);
        if (link == null) {
            return; // disconnected meanwhile
        }
        var gateway = gateways.get(link.provider());
        var now = clock.instant();
        access.with(link, token -> {
            for (var channel : sync.channels(link.id())) {
                var external = channel.externalId();
                if (external != null) {
                    quietly(() -> gateway.unwatch(token, channel.id(), external));
                }
            }
            for (var mirror : sync.mirrors(link.id()).values()) {
                if (mirror.endsAt().isAfter(now)) {
                    quietly(() -> gateway.delete(token, mirror.calendarId(), mirror.externalEventId()));
                }
            }
            return true;
        });
        String refresh = null;
        try {
            refresh = access.refreshToken(link);
        } catch (RuntimeException e) {
            log.warn("Calendar link {}: refresh token unreadable, not revoked: {}", link.id(), e.getMessage());
        }
        if (refresh != null) {
            gateway.revoke(refresh);
        }
        access.forget(link.id());
        links.delete(link.merchantId(), link.memberUserId(), link.provider());
    }

    @Override
    @Transactional
    public SourcesView list(String merchantId, String userId, CalendarProvider provider) {
        var link = twoWayLink(merchantId, userId, provider);
        if (link.needsReconnect() || !link.scopes().contains(CalendarScope.CALENDAR_LIST)) {
            return new SourcesView(
                    List.of(),
                    start(
                            merchantId,
                            userId,
                            provider,
                            EnumSet.of(CalendarScope.EVENTS, CalendarScope.CALENDAR_LIST),
                            false));
        }
        var chosen = sync.sources(link.id()).stream()
                .map(CalendarSyncRepository.Source::calendarId)
                .collect(Collectors.toSet());
        var gateway = gateways.get(provider);
        return access.with(link, gateway::calendars)
                .map(calendars -> new SourcesView(
                        calendars.stream()
                                .map(c -> new SourceOption(c.id(), c.name(), c.primary(), chosen.contains(c.id())))
                                .toList(),
                        null))
                .orElseGet(() -> new SourcesView(
                        List.of(), start(merchantId, userId, provider, EnumSet.of(CalendarScope.EVENTS), true)));
    }

    @Override
    @Transactional
    public SourcesView choose(String merchantId, String userId, CalendarProvider provider, List<String> calendarIds) {
        if (calendarIds.isEmpty()) {
            throw RuleViolation.of("calendarIds", "required", NONE);
        }
        // S-136: the link before its sources (the token refresh below may update it)
        var link = links.lockWaiting(twoWayLink(merchantId, userId, provider).id())
                .orElseThrow(() -> new NotFound("calendar", provider.code()));
        var gateway = gateways.get(provider);
        var remote = access.with(link, gateway::calendars).orElse(null);
        if (remote == null) {
            return list(merchantId, userId, provider);
        }
        var byId = remote.stream().collect(Collectors.toMap(CalendarGateway.RemoteCalendar::id, c -> c, (a, _) -> a));
        var names = new LinkedHashMap<String, String>();
        for (int i = 0; i < calendarIds.size(); i++) {
            var calendar = byId.get(calendarIds.get(i));
            if (calendar == null) {
                throw RuleViolation.of("calendarIds[%d]".formatted(i), "not_found", UNKNOWN);
            }
            names.put(calendar.id(), calendar.name());
        }
        // channels of calendars that are no longer chosen are stopped at the provider before their rows go
        access.with(link, token -> {
            for (var c : sync.channels(link.id())) {
                var external = c.externalId();
                if (external != null && !names.containsKey(c.calendarId())) {
                    quietly(() -> gateway.unwatch(token, c.id(), external));
                }
            }
            return true;
        });
        sync.replaceSources(link.id(), names);
        events.publishEvent(new CalendarConnected(link.id()));
        return list(merchantId, userId, provider);
    }

    private Link twoWayLink(String merchantId, String userId, CalendarProvider provider) {
        if (!provider.twoWay()) {
            throw new NotFound("calendar", provider.code());
        }
        return links.find(merchantId, userId, provider).orElseThrow(() -> new NotFound("calendar", provider.code()));
    }

    static void quietly(Runnable call) {
        try {
            call.run();
        } catch (CalendarGateway.GrantRevoked | CalendarGateway.Unauthorized e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Calendar provider call failed (ignored): {}", e.getMessage());
        }
    }
}
