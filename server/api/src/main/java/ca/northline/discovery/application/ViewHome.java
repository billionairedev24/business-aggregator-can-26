package ca.northline.discovery.application;

import java.util.Locale;

/** Use case: the home page's numbers for a city (S-46). */
public interface ViewHome {

    /** Longest accepted city name (the header's IP city is capped at 60 characters too). */
    int CITY_MAX = 60;

    HomeSummary of(String city, Locale locale);
}
