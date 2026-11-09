package ca.northline.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** S-190: the visitor's place is optional on the public reads, but a value that is sent must be well formed. */
class PlaceParamsTest {

    @Test
    void blankOrAbsentIsNotKnown() {
        assertThat(PlaceParams.city("city", null)).isNull();
        assertThat(PlaceParams.city("city", "")).isNull();
        assertThat(PlaceParams.city("city", "   ")).isNull();
    }

    @Test
    void placeNamesAreAccepted_trimmed() {
        assertThat(PlaceParams.city("city", "  Testville  ")).isEqualTo("Testville");
        assertThat(PlaceParams.city("city", "Saint-Jean-sur-Richelieu")).isEqualTo("Saint-Jean-sur-Richelieu");
        assertThat(PlaceParams.city("city", "St. John's")).isEqualTo("St. John's");
        assertThat(PlaceParams.city("city", "Trois-Rivières")).isEqualTo("Trois-Rivières");
        assertThat(PlaceParams.city("city", "L’Île-Perrot")).isEqualTo("L’Île-Perrot");
        assertThat(PlaceParams.city("city", "100 Mile House")).isEqualTo("100 Mile House");
        assertThat(PlaceParams.city("market", "x".repeat(60))).hasSize(60);
    }

    @Test
    void tooLongOrNotAPlaceNameIsA422WithTheFieldsName() {
        assertThatThrownBy(() -> PlaceParams.city("market", "x".repeat(61)))
                .isInstanceOfSatisfying(
                        RuleViolation.class,
                        e -> assertThat(e.getViolations())
                                .containsExactly(
                                        new RuleViolation.Violation("market", "length", "At most 60 characters.")));
        for (var bad : new String[] {"<script>", "Town; drop", "-Dash", "a/b", "Town\nville"}) {
            assertThatThrownBy(() -> PlaceParams.city("city", bad))
                    .as(bad)
                    .isInstanceOfSatisfying(
                            RuleViolation.class,
                            e -> assertThat(e.getViolations())
                                    .containsExactly(
                                            new RuleViolation.Violation("city", "format", "Enter a city name.")));
        }
    }

    @Test
    void aPointIsBothOrNeither_insideTheMap() {
        PlaceParams.point(null, null);
        PlaceParams.point(45.5, -73.6);
        assertThatThrownBy(() -> PlaceParams.point(45.5, null))
                .hasMessage("Send both lat and lng, or neither.")
                .isInstanceOfSatisfying(
                        RuleViolation.class,
                        e -> assertThat(e.getViolations().getFirst().field()).isEqualTo("lng"));
        assertThatThrownBy(() -> PlaceParams.point(null, -73.6)).hasMessage("Send both lat and lng, or neither.");
        assertThatThrownBy(() -> PlaceParams.point(91.0, 0.0)).hasMessage("Latitude must be between -90 and 90.");
        assertThatThrownBy(() -> PlaceParams.point(0.0, -181.0)).hasMessage("Longitude must be between -180 and 180.");
        assertThatThrownBy(() -> PlaceParams.point(Double.NaN, 0.0)).hasMessage("Latitude must be between -90 and 90.");
    }

    @Test
    void distanceIsGreatCircleKm() {
        assertThat(PlaceParams.km(0, 0, 0, 0)).isZero();
        // one degree of latitude ≈ 111.2 km
        assertThat(PlaceParams.km(45, -75, 46, -75)).isBetween(111.0, 111.4);
        assertThat(PlaceParams.km(10, 20, 30, 40)).isEqualTo(PlaceParams.km(30, 40, 10, 20));
    }
}
