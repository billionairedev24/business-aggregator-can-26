package ca.northline.studio.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-74: the appointments calendar's dim cells next to the jobs (design 02 week view): "Open slot" — when a job of
 * {@code durationMin} could still be booked — and "Held for quote · M. Tran" — the time a sent quote proposes.
 */
public interface CalendarCells {

    Cells of(String merchantId, LocalDate from, int days, int durationMin);

    /**
     * @param openSlots the start of each run of free bookable time, at most {@link #OPEN_SLOTS_PER_DAY} a day
     */
    record Cells(List<OpenSlot> openSlots, List<QuoteHold> quoteHolds) {
        public Cells {
            openSlots = List.copyOf(openSlots);
            quoteHolds = List.copyOf(quoteHolds);
        }
    }

    record OpenSlot(Instant startsAt) {}

    record QuoteHold(
            String quoteId, String requestId, @Nullable String ref, String customerName, Instant startsAt, int durationMin) {}

    int OPEN_SLOTS_PER_DAY = 3;
}
