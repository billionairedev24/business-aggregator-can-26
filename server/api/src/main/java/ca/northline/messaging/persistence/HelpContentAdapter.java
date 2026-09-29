package ca.northline.messaging.persistence;

import static ca.northline.messaging.persistence.MessagingSql.instant;
import static ca.northline.messaging.persistence.MessagingSql.strings;

import ca.northline.messaging.application.BrowseHelp.Article;
import ca.northline.messaging.application.BrowseHelp.ArticleSummary;
import ca.northline.messaging.application.BrowseHelp.StatusComponent;
import ca.northline.messaging.application.BrowseHelp.Topic;
import ca.northline.messaging.application.HelpContentStore;
import ca.northline.messaging.domain.Portal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Help topics, articles and platform status. Search is Postgres full text in the reader's language ({@code english}
 * or {@code french} configuration) over title and body, any word matching, ranked with titles first.
 */
@Repository
@RequiredArgsConstructor
class HelpContentAdapter implements HelpContentStore {

    private static final String ARTICLE_COLUMNS = """
            a.slug, coalesce(a.title_i18n ->> :lang, a.title_i18n ->> 'en') as title,
            coalesce(a.section_i18n ->> :lang, a.section_i18n ->> 'en') as section, a.read_min
            """;

    private final JdbcClient jdbc;

    @Override
    public List<Topic> topics(Portal portal, String lang) {
        return jdbc.sql("""
                        select t.key, coalesce(t.name_i18n ->> :lang, t.name_i18n ->> 'en') as name, t.case_topic,
                               (select count(*) from messaging.help_articles a
                                 where t.key = any(a.topic_keys) and :portal = any(a.portals)) as articles
                          from messaging.help_topics t
                         where :portal = any(t.portals)
                         order by t.position
                        """)
                .param("lang", lang)
                .param("portal", portal.code())
                .query((rs, _) -> new Topic(
                        rs.getString("key"), rs.getString("name"), rs.getInt("articles"), rs.getString("case_topic")))
                .list();
    }

    @Override
    public List<ArticleSummary> suggested(Portal portal, String lang) {
        return jdbc.sql("select " + ARTICLE_COLUMNS + """
                          from messaging.help_articles a
                         where :portal = any(a.portals) and a.featured is not null
                         order by a.featured, a.slug
                        """)
                .param("lang", lang)
                .param("portal", portal.code())
                .query((rs, _) -> summary(rs))
                .list();
    }

    @Override
    public List<ArticleSummary> search(Portal portal, String lang, String query, int limit) {
        var config = "fr".equals(lang) ? "french" : "english";
        return jdbc.sql("select " + ARTICLE_COLUMNS + """
                          from messaging.help_articles a,
                               lateral (select nullif(replace(plainto_tsquery(cast(:cfg as regconfig), :q)::text,
                                                              '&', '|'), '')::tsquery as q) query
                         where :portal = any(a.portals)
                           and query.q is not null
                           and to_tsvector(cast(:cfg as regconfig),
                                           coalesce(a.title_i18n ->> :lang, a.title_i18n ->> 'en') || ' '
                                           || coalesce(a.body_i18n ->> :lang, a.body_i18n ->> 'en')) @@ query.q
                         order by ts_rank(setweight(to_tsvector(cast(:cfg as regconfig),
                                                    coalesce(a.title_i18n ->> :lang, a.title_i18n ->> 'en')), 'A')
                                          || setweight(to_tsvector(cast(:cfg as regconfig),
                                                    coalesce(a.body_i18n ->> :lang, a.body_i18n ->> 'en')), 'D'),
                                          query.q) desc,
                                  a.slug
                         limit :limit
                        """)
                .param("lang", lang)
                .param("cfg", config)
                .param("q", query)
                .param("portal", portal.code())
                .param("limit", limit)
                .query((rs, _) -> summary(rs))
                .list();
    }

    @Override
    public List<ArticleSummary> inTopic(Portal portal, String lang, String topicKey) {
        return jdbc.sql("select " + ARTICLE_COLUMNS + """
                          from messaging.help_articles a
                         where :portal = any(a.portals) and :topic = any(a.topic_keys)
                         order by a.featured nulls last, a.read_min, a.slug
                        """)
                .param("lang", lang)
                .param("portal", portal.code())
                .param("topic", topicKey)
                .query((rs, _) -> summary(rs))
                .list();
    }

    @Override
    public Optional<Article> article(Portal portal, String lang, String slug) {
        return jdbc.sql("select " + ARTICLE_COLUMNS + """
                               , coalesce(a.body_i18n ->> :lang, a.body_i18n ->> 'en') as body, a.topic_keys
                          from messaging.help_articles a
                         where :portal = any(a.portals) and a.slug = :slug
                        """)
                .param("lang", lang)
                .param("portal", portal.code())
                .param("slug", slug)
                .query((rs, _) -> new Article(
                        rs.getString("slug"),
                        rs.getString("title"),
                        rs.getString("section"),
                        rs.getInt("read_min"),
                        rs.getString("body"),
                        strings(rs, "topic_keys")))
                .optional();
    }

    @Override
    public List<StatusComponent> status(String lang) {
        return jdbc.sql("""
                        select key, coalesce(name_i18n ->> :lang, name_i18n ->> 'en') as name, state,
                               coalesce(note_i18n ->> :lang, note_i18n ->> 'en') as note, since
                          from messaging.status_components
                         order by position
                        """)
                .param("lang", lang)
                .query((rs, _) -> new StatusComponent(
                        rs.getString("key"),
                        rs.getString("name"),
                        rs.getString("state"),
                        rs.getString("note"),
                        instant(rs, "since")))
                .list();
    }

    private static ArticleSummary summary(ResultSet rs) throws SQLException {
        return new ArticleSummary(
                rs.getString("slug"), rs.getString("title"), rs.getString("section"), rs.getInt("read_min"));
    }
}
