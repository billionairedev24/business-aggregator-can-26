package ca.northline.uat.application;

import ca.northline.uat.api.UatReadiness;
import java.util.Locale;

/** Inbound port: the go/no-go report for the console, and as CSV. */
public interface UatReports extends UatReadiness {

    String csv(Locale locale);
}
