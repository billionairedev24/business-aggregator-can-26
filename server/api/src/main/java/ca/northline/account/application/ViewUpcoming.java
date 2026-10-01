package ca.northline.account.application;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The home page's "Your week" (S-46 contract, docs/CONSUMER_WEB_PLAN.md § Your week): the person's orders, bookings and
 * quotes of the next seven days, in their language.
 */
public interface ViewUpcoming {

    /** @param tone {@code accent | neutral | accent-2} */
    record Upcoming(String id, String title, @Nullable String subtitle, String state, String tone, String href) {}

    List<Upcoming> upcoming(String userId, Locale locale);
}
