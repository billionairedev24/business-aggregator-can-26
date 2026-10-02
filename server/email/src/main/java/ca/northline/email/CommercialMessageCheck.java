package ca.northline.email;

import java.net.URI;
import java.util.ArrayList;

/**
 * The CASL content check of a commercial electronic message (S-108): it must identify the sender — the legal name and
 * the mailing address, both from configuration — and carry a working unsubscribe mechanism. Run on every commercial
 * email after rendering ({@link EmailTemplates#render}) and on every commercial SMS before it goes (the worker); a
 * message that fails is never sent — it is a bug, not a delivery problem.
 */
public final class CommercialMessageCheck {

    private CommercialMessageCheck() {}

    /** Both bodies name the legal sender and the mailing address and link the unsubscribe URL. */
    public static void email(
            String template, RenderedEmail email, String legalName, String mailingAddress, URI unsubscribe) {
        var missing = new ArrayList<String>();
        var link = unsubscribe.toString();
        if (!email.text().contains(legalName) || !email.html().contains(html(legalName))) {
            missing.add("the legal name");
        }
        if (!email.text().contains(mailingAddress) || !email.html().contains(html(mailingAddress))) {
            missing.add("the mailing address");
        }
        if (!email.text().contains(link) || !email.html().contains("href=\"" + html(link) + "\"")) {
            missing.add("the unsubscribe link");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Commercial email " + template + " lacks " + String.join(", ", missing) + " (CASL s. 6(2))");
        }
    }

    /** The SMS names the legal sender and carries its opt-out link. */
    public static String sms(String text, String legalName, URI optOut) {
        if (!text.contains(legalName) || !text.contains(optOut.toString())) {
            throw new IllegalStateException("Commercial SMS lacks the legal name or the opt-out link (CASL s. 6(2))");
        }
        return text;
    }

    /** Markup escaping as Thymeleaf writes text into HTML. */
    static String html(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
