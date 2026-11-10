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
    void postalCodesOfAnyCountryAreAccepted_normalised() {
        assertThat(PlaceParams.postalCode("code", "  t2p   1b5 ")).isEqualTo("T2P 1B5");
        assertThat(PlaceParams.postalCode("code", "90210")).isEqualTo("90210");
        assertThat(PlaceParams.postalCode("code", "90210-1234")).isEqualTo("90210-1234");
        assertThat(PlaceParams.postalCode("code", "SW1A 1AA")).isEqualTo("SW1A 1AA");
        assertThat(PlaceParams.postalCode("code", "110-0001")).isEqualTo("110-0001");
        assertThat(PlaceParams.postalCode("code", "x".repeat(12))).hasSize(12);
    }

    @Test
    void postalCodeRulesAndMessages() {
        for (var blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> PlaceParams.postalCode("code", blank))
                    .isInstanceOfSatisfying(
                            RuleViolation.class,
                            e -> assertThat(e.getViolations())
                                    .containsExactly(new RuleViolation.Violation(
                                            "code", "required", "Enter a postal or ZIP code.")));
        }
        assertThatThrownBy(() -> PlaceParams.postalCode("code", "1".repeat(13))).hasMessage("At most 12 characters.");
        for (var bad : new String[] {"T2P_1B5", "<b>", "-1234", "12 34-", "T2P/1B5", "ÉÉ1"}) {
            assertThatThrownBy(() -> PlaceParams.postalCode("code", bad))
                    .as(bad)
                    .hasMessage("Use letters, digits, spaces and hyphens only.");
        }
        assertThat(PlaceParams.postalOrNull("bad_code")).isNull();
        assertThat(PlaceParams.postalOrNull("t2p1b5")).isEqualTo("T2P1B5");
    }

    @Test
    void countryIsTwoLetters_orNotGiven() {
        assertThat(PlaceParams.country("country", null)).isNull();
        assertThat(PlaceParams.country("country", " ")).isNull();
        assertThat(PlaceParams.country("country", "us")).isEqualTo("US");
        for (var bad : new String[] {"USA", "U", "1A", "XX", "T1"}) {
            assertThatThrownBy(() -> PlaceParams.country("country", bad))
                    .as(bad)
                    .hasMessage("Choose a country from the list.");
        }
        assertThat(PlaceParams.isCountry("XX")).isFalse();
        assertThat(PlaceParams.isCountry("FR")).isTrue();
    }

    @Test
    void distanceIsGreatCircleKm() {
        assertThat(PlaceParams.km(0, 0, 0, 0)).isZero();
        // one degree of latitude ≈ 111.2 km
        assertThat(PlaceParams.km(45, -75, 46, -75)).isBetween(111.0, 111.4);
        assertThat(PlaceParams.km(10, 20, 30, 40)).isEqualTo(PlaceParams.km(30, 40, 10, 20));
    }
}
