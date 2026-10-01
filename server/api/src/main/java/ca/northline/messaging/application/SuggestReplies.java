package ca.northline.messaging.application;

import java.util.List;
import java.util.Locale;

/**
 * S-131: reply suggestions in Messages, from the thread's recent messages. Never sent: the composer shows them as
 * AI-suggested chips, and a click only puts the text into the box for the person to edit and send.
 */
public interface SuggestReplies {

    /** One message of the thread as the model sees it: who (customer / business / northline) and the text. */
    record Turn(String from, String text) {}

    record Suggestions(List<String> replies, boolean aiAssisted, String model, String prompt) {}

    Suggestions suggest(String merchantId, String threadId, BrowseInbox.Viewer viewer, Locale locale);

    /** The same from the turns (evals). */
    Suggestions suggest(String merchantId, String userId, String refType, List<Turn> turns, Locale locale);
}
