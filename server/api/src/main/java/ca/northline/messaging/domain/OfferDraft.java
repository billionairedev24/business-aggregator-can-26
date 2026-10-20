package ca.northline.messaging.domain;

import ca.northline.shared.RuleViolation;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * S-157: an offer Northline sends to the consenting customers of one market — one channel, its words in both official
 * languages and the page of the site it opens. No personal data: the same words go to everyone.
 */
public record OfferDraft(
        String market, String channel, String titleEn, String bodyEn, String titleFr, String bodyFr, String path) {

    public static final int TITLE_MAX = 80;
    public static final int BODY_MAX = 600;
    public static final String MARKET = "Choose a market Northline serves.";
    public static final String CHANNEL = "Choose email or text.";
    public static final String TITLE_EN = "Write the English title (up to 80 characters).";
    public static final String TITLE_FR = "Write the French title (up to 80 characters).";
    public static final String BODY_EN = "Write the English message (up to 600 characters).";
    public static final String BODY_FR = "Write the French message (up to 600 characters).";
    public static final String PATH = "Link to a page of the site, starting with /.";

    private static final Pattern PATH_RULE = Pattern.compile("^/[A-Za-z0-9/_.?=&%-]{0,199}$");

    public OfferDraft {
        market = market.strip().toUpperCase(java.util.Locale.ROOT);
        channel = channel.strip();
        titleEn = titleEn.strip();
        bodyEn = bodyEn.strip();
        titleFr = titleFr.strip();
        bodyFr = bodyFr.strip();
        path = path.strip();
        var errors = new ArrayList<RuleViolation.Violation>();
        if (!List.of("email", "sms").contains(channel)) {
            errors.add(new RuleViolation.Violation("channel", "allowed", CHANNEL));
        }
        text(errors, "titleEn", titleEn, TITLE_MAX, TITLE_EN);
        text(errors, "bodyEn", bodyEn, BODY_MAX, BODY_EN);
        text(errors, "titleFr", titleFr, TITLE_MAX, TITLE_FR);
        text(errors, "bodyFr", bodyFr, BODY_MAX, BODY_FR);
        if (!PATH_RULE.matcher(path).matches() || path.startsWith("//")) {
            errors.add(new RuleViolation.Violation("path", "format", PATH));
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
    }

    public boolean email() {
        return "email".equals(channel);
    }

    private static void text(
            List<RuleViolation.Violation> errors, String field, String value, int max, String message) {
        if (value.isEmpty() || value.length() > max || (value.contains("\n") && field.startsWith("title"))) {
            errors.add(new RuleViolation.Violation(field, value.isEmpty() ? "required" : "length", message));
        }
    }
}
