package ca.northline.messaging.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Help centre: topics and articles for the business's portal (en or fr), search, and platform status. */
public interface BrowseHelp {

    record Topic(String key, String name, int articleCount, String caseTopic) {}

    record ArticleSummary(String slug, String title, String section, int readMin) {}

    record Article(String slug, String title, String section, int readMin, String body, List<String> topicKeys) {}

    record StatusComponent(
            String key,
            String name,
            String state,
            @Nullable String note,
            @Nullable Instant since) {}

    List<Topic> topics(String merchantId, Locale locale);

    /** Search results for {@code query}, the articles of {@code topic}, or the portal's suggested articles. */
    List<ArticleSummary> articles(String merchantId, @Nullable String query, @Nullable String topic, Locale locale);

    Article article(String merchantId, String slug, Locale locale);

    List<StatusComponent> status(Locale locale);
}
