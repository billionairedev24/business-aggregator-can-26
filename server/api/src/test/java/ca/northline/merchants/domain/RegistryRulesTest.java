package ca.northline.merchants.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.merchants.domain.RegistryCheck.Answer;
import ca.northline.merchants.domain.RegistryCheck.Trigger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** S-23: business-name matching and how an answer becomes an outcome. */
class RegistryRulesTest {

    static final Instant T = Instant.parse("2026-09-30T16:00:00Z");

    @ParameterizedTest(name = "{0} ~ {1} → {2}")
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "Prairie Wrench Automotive Limited | PRAIRIE WRENCH AUTOMOTIVE LTD. | true",
                "2201456 Alberta Ltd.              | 2201456 ALBERTA LTD.            | true",
                "Pho Dau Bo                        | PHO DAU BO                      | true",
                "The Bread & Butter Co.            | BREAD AND BUTTER COMPANY        | true",
                "Boulangerie Côté Ltée             | BOULANGERIE COTE                | true",
                "Prairie Wrench                    | Prairie Wrench Parts Ltd.       | false",
                "Aspen Wrench                      | Aspen Wrenches Inc.             | false",
            })
    void businessNames(String entered, String record, boolean matches) {
        assertThat(BusinessNames.matches(List.of(entered), record)).isEqualTo(matches);
    }

    static RegistryQuery query() {
        return new RegistryQuery(
                RegistrySource.CALGARY_BUSINESS_LICENCES,
                RegistrySubject.MUNICIPAL_LICENCE,
                null,
                "BL 1",
                List.of("Legal Name Ltd.", "Pho Dau Bo"));
    }

    static RegistryCheck check(Answer answer, Trigger trigger) {
        return RegistryCheck.of("c", "m", "v", query(), answer, trigger, T, java.time.ZoneId.of("America/Edmonton"));
    }

    static Answer found(String name, RegistryRecord.Standing standing, LocalDate expires) {
        return new Answer.Found(new RegistryRecord(name, "BL 1", standing, "x", expires), "ref");
    }

    @Test
    void anyEnteredNameMatches_activeAndCurrent_isAMatch_withoutAReview() {
        var c = check(found("PHO DAU BO", RegistryRecord.Standing.ACTIVE, LocalDate.of(2027, 1, 1)), Trigger.INITIAL);
        assertThat(c.getOutcome()).isEqualTo(RegistryOutcome.MATCHED);
        assertThat(c.getReviewState()).isNull();
        assertThat(c.getRecordExpiresOn()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void mismatchReasons_andReviews() {
        var c = check(
                found("Someone Else", RegistryRecord.Standing.INACTIVE, LocalDate.of(2026, 9, 1)), Trigger.INITIAL);
        assertThat(c.getOutcome()).isEqualTo(RegistryOutcome.MISMATCH);
        assertThat(c.getReasons()).containsExactly("name", "status", "expired");
        assertThat(c.getReviewState()).isEqualTo(RegistryCheck.ReviewState.OPEN);

        assertThat(check(new Answer.NotFound(null), Trigger.INITIAL).getOutcome())
                .isEqualTo(RegistryOutcome.NOT_FOUND);
        assertThat(check(new Answer.Manual(null), Trigger.INITIAL).getReviewState())
                .isEqualTo(RegistryCheck.ReviewState.OPEN);
        // a re-check that can't reach the source doesn't bother an agent; an initial lookup does
        assertThat(check(new Answer.Unavailable("down"), Trigger.RECHECK).getReviewState())
                .isNull();
        assertThat(check(new Answer.Unavailable("down"), Trigger.INITIAL).getReviewState())
                .isEqualTo(RegistryCheck.ReviewState.OPEN);
    }
}
