package ca.northline.booking.application;

import ca.northline.booking.domain.QuoteEnums.LineKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * S-131: suggested quote lines from a customer's job request — kinds, descriptions and quantities, never prices. The
 * composer adds them as editable rows marked AI-suggested; the business prices them and sends the quote itself.
 */
public interface DraftQuoteLines {

    /** The job as the business sees it in the quote inbox. */
    record Job(String title, @Nullable String description, @Nullable String area) {}

    record Line(LineKind kind, String description, BigDecimal qty) {}

    record Suggestion(List<Line> lines, List<String> questions, boolean aiAssisted, String model, String prompt) {}

    Suggestion suggest(String merchantId, String requestId, String userId, @Nullable String notes, Locale locale);

    /** The same from a job's facts (evals). */
    Suggestion suggest(String merchantId, String userId, Job job, @Nullable String notes, Locale locale);
}
