package ca.northline.hire;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.hire.domain.ServiceKind;
import ca.northline.hire.domain.TrustRank;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ServiceKindTest {

    @Test
    void bookingTypeComesFromTheGroupWithLeafExceptions() {
        assertThat(ServiceKind.of("service.automotive.mobile-mechanic", null)).isEqualTo(ServiceKind.VISIT);
        assertThat(ServiceKind.of("service.cleaning-and-property.house-cleaning", null))
                .isEqualTo(ServiceKind.HOME);
        assertThat(ServiceKind.of("service.cleaning-and-property.movers", null)).isEqualTo(ServiceKind.EVENT);
        assertThat(ServiceKind.of("service.events-and-hospitality.cocktail-and-mocktail-bar", null))
                .isEqualTo(ServiceKind.EVENT);
        assertThat(ServiceKind.of("service.personal-care-and-wellness.barber-and-hair", null))
                .isEqualTo(ServiceKind.APPOINTMENT);
        assertThat(ServiceKind.of("service.professional.real-estate-agent", null))
                .isEqualTo(ServiceKind.CONSULT);
        assertThat(ServiceKind.of("service.unknown-group.thing", null)).isEqualTo(ServiceKind.VISIT);
        // a stored catalogue.categories.booking_type wins
        assertThat(ServiceKind.of("service.automotive.mobile-mechanic", "consult"))
                .isEqualTo(ServiceKind.CONSULT);
        assertThat(ServiceKind.of("service.automotive.mobile-mechanic", "null")).isEqualTo(ServiceKind.VISIT);
    }

    @Test
    void vehicleQuestionsOnlyForAutomotive() {
        assertThat(ServiceKind.vehicle("service.automotive.detailing")).isTrue();
        assertThat(ServiceKind.vehicle("service.home-trades.plumber")).isFalse();
    }

    @Test
    void quotesForVisitsAndEventsOnly() {
        assertThat(Stream.of(ServiceKind.values()).filter(ServiceKind::quoteable))
                .containsExactly(ServiceKind.VISIT, ServiceKind.EVENT);
        assertThat(ServiceKind.EVENT.quoteOnly()).isTrue();
        assertThat(ServiceKind.APPOINTMENT.comesToCustomer()).isFalse();
    }

    @Test
    void trustRankOrdersByTierThenOnTimeDisputesRebookRating() {
        var signals = List.of(
                new TrustRank.Signals("registered", 100.0, 0.0, 90.0, 5.0, "a-registered"),
                new TrustRank.Signals("trusted", null, null, null, 5.0, "b-trusted-unscored"),
                new TrustRank.Signals("trusted", 95.0, 1.0, 60.0, 4.8, "c-trusted-95"),
                new TrustRank.Signals("trusted", 99.0, 0.5, 40.0, 4.1, "d-trusted-99"),
                new TrustRank.Signals("trusted", 95.0, 0.2, 60.0, 4.8, "e-trusted-95-fewer-disputes"),
                new TrustRank.Signals("master", 80.0, 3.0, 10.0, 3.9, "f-master"));
        assertThat(signals.stream().sorted(TrustRank.by(s -> s)).map(TrustRank.Signals::name))
                .containsExactly(
                        "f-master",
                        "d-trusted-99",
                        "e-trusted-95-fewer-disputes",
                        "c-trusted-95",
                        "b-trusted-unscored",
                        "a-registered");
    }
}
