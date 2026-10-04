package ca.northline.golive.domain;

import ca.northline.shared.RuleViolation;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The hypercare rota (docs/runbooks/go-live.md § Hypercare): {@value #DAYS} days from the start, each with a primary
 * and a secondary engineer and a business contact, taken in turn from the three lists (day n gets the n-th of each,
 * wrapping). When the turn would make the primary their own secondary, the next secondary in the list takes the day.
 */
public final class HypercareRotation {

    public static final int DAYS = 14;
    public static final String PRIMARIES = "Choose 1 to 14 people for the primary on-call.";
    public static final String SECONDARIES = "Choose 1 to 14 people for the secondary on-call.";
    public static final String BUSINESS = "Choose 1 to 14 business contacts.";
    public static final String TWO_PEOPLE = "The primary and the secondary must be two different people each day.";

    private HypercareRotation() {}

    public record Day(LocalDate date, String primary, String secondary, String business) {}

    public static List<Day> plan(
            LocalDate start, List<String> primaries, List<String> secondaries, List<String> business) {
        check("primaries", primaries, PRIMARIES);
        check("secondaries", secondaries, SECONDARIES);
        check("businessContacts", business, BUSINESS);
        return IntStream.range(0, DAYS)
                .mapToObj(n -> {
                    var primary = primaries.get(n % primaries.size());
                    var secondary = IntStream.range(0, secondaries.size())
                            .mapToObj(k -> secondaries.get((n + k) % secondaries.size()))
                            .filter(s -> !s.equals(primary))
                            .findFirst()
                            .orElseThrow(() -> RuleViolation.of("secondaries", "distinct", TWO_PEOPLE));
                    return new Day(start.plusDays(n), primary, secondary, business.get(n % business.size()));
                })
                .toList();
    }

    private static void check(String field, List<String> people, String message) {
        if (people.isEmpty() || people.size() > DAYS || people.stream().anyMatch(String::isBlank)) {
            throw RuleViolation.of(field, "size", message);
        }
    }
}
