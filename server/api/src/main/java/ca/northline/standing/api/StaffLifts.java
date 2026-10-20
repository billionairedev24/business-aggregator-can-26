package ca.northline.standing.api;

import java.time.Duration;
import java.util.Set;

/**
 * S-156: businesses (or customers) whose consequence of {@code kind} staff lifted — by hand or by granting an appeal —
 * within {@code within}. The rules' jobs leave them alone for that long, so a lift isn't undone the next night.
 */
public interface StaffLifts {

    /** @param kind {@code suspended} | {@code search_hidden} | {@code instant_book_off} | {@code no_show_warning} */
    Set<String> liftedWithin(String kind, Duration within);
}
