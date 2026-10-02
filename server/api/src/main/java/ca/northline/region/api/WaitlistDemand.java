package ca.northline.region.api;

import java.util.Map;

/** S-95: how many people are on the waitlist of each province (counts only), for the console's demand gaps. */
public interface WaitlistDemand {

    /** Province code → people waiting, for provinces with anyone waiting. */
    Map<String, Long> byProvince();
}
