package ca.northline.worker.search;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.searchindex.ListingDocument.MinuteRange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Weekly hours → minute-of-week ranges; completion inputs. */
class OpenHoursTest {

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void weekdaysBecomeMinutesOfTheWeek_mondayMidnightIsZero() {
        assertThat(OpenHours.of(Map.of(1, List.of("[[\"09:00\",\"17:00\"]]")), json))
                .containsExactly(new MinuteRange(540, 1020));
        assertThat(OpenHours.of(Map.of(7, List.of("[[\"10:00\",\"24:00\"]]")), json))
                .containsExactly(new MinuteRange(6 * 1440 + 600, 7 * 1440));
    }

    @Test
    void pastMidnightContinuesNextDay_sundayWrapsToMonday() {
        assertThat(OpenHours.of(Map.of(5, List.of("[[\"18:00\",\"02:00\"]]")), json))
                .containsExactly(new MinuteRange(4 * 1440 + 1080, 5 * 1440 + 120));
        assertThat(OpenHours.of(Map.of(7, List.of("[[\"22:00\",\"01:00\"]]")), json))
                .containsExactly(new MinuteRange(0, 60), new MinuteRange(6 * 1440 + 1320, 7 * 1440));
    }

    @Test
    void membersHoursAreMerged_badRangesIgnored() {
        var ranges = OpenHours.of(
                Map.of(
                        2,
                        List.of(
                                "[[\"08:00\",\"12:00\"],[\"13:00\",\"17:00\"]]",
                                "[[\"11:00\",\"14:00\"]]",
                                "[[\"x\"]]")),
                json);
        assertThat(ranges).containsExactly(new MinuteRange(1440 + 480, 1440 + 1020));
        assertThat(OpenHours.of(Map.of(3, List.of("[]")), json)).isEmpty();
        assertThat(OpenHours.minuteOfDay("17:45")).isEqualTo(1065);
        assertThat(OpenHours.minuteOfDay("soon")).isNull();
    }

    @Test
    void completionInputsStartAtEveryWord() {
        assertThat(DocumentBuilder.inputs(List.of("Country sourdough loaf")))
                .containsExactly("Country sourdough loaf", "sourdough loaf", "loaf");
        assertThat(DocumentBuilder.inputs(List.of("Pho — spécial"))).containsExactly("Pho — spécial", "spécial");
        assertThat(DocumentBuilder.trustRank("master")).isEqualTo(3);
        assertThat(DocumentBuilder.trustRank(null)).isZero();
    }
}
