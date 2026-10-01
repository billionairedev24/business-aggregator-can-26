package ca.northline.search.domain;

import ca.northline.searchindex.SearchLanguage;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** What the person has typed so far, for the suggestions under the search box. */
public record SuggestQuery(String prefix, String market, SearchLanguage language, Set<SearchKind> kinds, int size) {

    public static final int DEFAULT_SIZE = 6;

    public SuggestQuery {
        prefix = prefix.strip();
        kinds = Set.copyOf(kinds);
        var problems = new ArrayList<Violation>();
        if (prefix.isEmpty() || prefix.chars().noneMatch(Character::isLetterOrDigit)) {
            problems.add(new Violation("q", "required", SearchMessages.PREFIX_REQUIRED));
        } else if (prefix.length() > SearchMessages.MAX_QUERY) {
            problems.add(new Violation("q", "length", SearchMessages.QUERY_TOO_LONG));
        }
        if (!SearchQuery.MARKET.matcher(market).matches()) {
            problems.add(new Violation("market", "format", SearchMessages.MARKET));
        }
        if (size < 1 || size > SearchMessages.MAX_SUGGESTIONS) {
            problems.add(new Violation("size", "range", SearchMessages.SUGGEST_SIZE));
        }
        if (!problems.isEmpty()) {
            throw new RuleViolation(List.copyOf(problems));
        }
    }
}
