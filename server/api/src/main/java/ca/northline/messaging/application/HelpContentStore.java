package ca.northline.messaging.application;

import ca.northline.messaging.application.BrowseHelp.Article;
import ca.northline.messaging.application.BrowseHelp.ArticleSummary;
import ca.northline.messaging.application.BrowseHelp.StatusComponent;
import ca.northline.messaging.application.BrowseHelp.Topic;
import ca.northline.messaging.domain.Portal;
import java.util.List;
import java.util.Optional;

/** Outbound port: help topics, articles (full-text search per language) and platform status. */
public interface HelpContentStore {

    List<Topic> topics(Portal portal, String lang);

    List<ArticleSummary> suggested(Portal portal, String lang);

    List<ArticleSummary> search(Portal portal, String lang, String query, int limit);

    List<ArticleSummary> inTopic(Portal portal, String lang, String topicKey);

    Optional<Article> article(Portal portal, String lang, String slug);

    List<StatusComponent> status(String lang);
}
