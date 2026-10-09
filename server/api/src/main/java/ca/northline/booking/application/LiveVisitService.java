package ca.northline.booking.application;

import ca.northline.booking.api.VisitEtas;
import ca.northline.booking.domain.GeoPoint;
import ca.northline.booking.domain.VisitEta;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.MerchantRole;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** {@link ShareTravel} and {@link VisitEtas} over {@link ProviderPositions} (Valkey) and the job site. */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(VisitEtaProperties.class)
class LiveVisitService implements ShareTravel, VisitEtas {

    static final String EN_ROUTE = "en_route";

    private final VisitStore visits;
    private final ProviderPositions positions;
    private final VisitEtaProperties properties;
    private final Clock clock;

    @Override
    public Shared share(CurrentMember actor, String bookingId, double lat, double lng) {
        var point = new GeoPoint(lat, lng);
        var visit = own(actor, bookingId);
        if (!EN_ROUTE.equals(visit.state())) {
            throw new Conflict("not_en_route", NOT_EN_ROUTE);
        }
        var interval = (int) properties.shareInterval().toSeconds();
        if (!positions.allow(bookingId, properties.shareInterval())) {
            return new Shared(
                    false,
                    interval,
                    positions
                            .latest(bookingId)
                            .map(ProviderPositions.Position::at)
                            .orElse(null));
        }
        var now = clock.instant();
        positions.put(bookingId, new ProviderPositions.Position(point.lat(), point.lng(), now), properties.ttl());
        return new Shared(true, interval, now);
    }

    @Override
    public void stop(CurrentMember actor, String bookingId) {
        own(actor, bookingId);
        positions.clear(bookingId);
    }

    /** The member doing the job (an owner may share for an unassigned one). */
    private VisitStore.Visit own(CurrentMember actor, String bookingId) {
        var visit = visits.visit(bookingId)
                .filter(v -> v.merchantId().equals(actor.merchantId()))
                .orElseThrow(() -> new NotFound("job", bookingId));
        var doer = visit.memberUserId();
        var mine = Objects.equals(doer, actor.userId()) || (doer == null && actor.role() == MerchantRole.OWNER);
        if (!mine) {
            throw new Conflict("not_your_job", NOT_YOURS);
        }
        return visit;
    }

    @Override
    public Optional<Eta> eta(String customerId, String bookingId) {
        return visits.visit(bookingId)
                .filter(v -> customerId.equals(v.customerId()))
                .map(v -> {
                    if (!EN_ROUTE.equals(v.state())) {
                        return new Eta(v.state(), false, null, null, null, "straight_line");
                    }
                    var now = clock.instant();
                    var latest = positions
                            .latest(bookingId)
                            .filter(p -> !VisitEta.stale(p.at(), now, properties.ttl()))
                            .orElse(null);
                    if (latest == null) {
                        return new Eta(v.state(), false, null, null, null, "straight_line");
                    }
                    var siteLat = v.siteLat();
                    var siteLng = v.siteLng();
                    if (siteLat == null || siteLng == null) {
                        return new Eta(v.state(), true, null, null, latest.at(), "straight_line");
                    }
                    var km = VisitEta.km(new GeoPoint(latest.lat(), latest.lng()), new GeoPoint(siteLat, siteLng));
                    return new Eta(
                            v.state(),
                            true,
                            VisitEta.minutes(km, properties.minutesPerKm()),
                            VisitEta.rounded(km),
                            latest.at(),
                            "straight_line");
                });
    }
}
