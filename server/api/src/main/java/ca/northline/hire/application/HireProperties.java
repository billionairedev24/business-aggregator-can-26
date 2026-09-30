package ca.northline.hire.application;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code northline.hire}: the region defaults of the consumer Services journey until the region configuration (S-134)
 * has markets — configuration, never code (DECISIONS "Region-neutral by design"). A business's own province wins over
 * {@code defaultProvince} wherever it is recorded.
 *
 * @param defaultCity the market a visitor without any location is shown (the site's first live market)
 * @param defaultProvince the province of a business whose onboarding didn't record one (tax on its bookings)
 * @param timeZone the market's time zone: "today", calendar days and a requested date's 9 am
 */
@ConfigurationProperties("northline.hire")
public record HireProperties(String defaultCity, String defaultProvince, ZoneId timeZone) {}
