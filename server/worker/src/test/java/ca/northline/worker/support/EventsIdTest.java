package ca.northline.worker.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * Engineering follow-ups (flaky CommercialNoticesTest): test ids must sort in the order they were made, like the ULIDs
 * the api writes — rows with the same time are ordered by id (the newest consent record wins).
 */
class EventsIdTest {

    @Test
    void idsSortInTheOrderTheyWereMade_andUseTheCrockfordAlphabet() {
        var ids = new ArrayList<String>();
        for (var i = 0; i < 200_000; i++) {
            ids.add(Events.id());
        }
        assertThat(ids).isSorted().doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> assertThat(id).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}"));
    }
}
