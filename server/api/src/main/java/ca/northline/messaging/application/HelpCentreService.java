package ca.northline.messaging.application;

import static ca.northline.messaging.application.MessagingInboxService.lang;

import ca.northline.messaging.domain.Portal;
import ca.northline.shared.NotFound;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Help centre reads: portal-specific topics and articles in the caller's language, search, platform status. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class HelpCentreService implements BrowseHelp {

    static final int SEARCH_LIMIT = 8;

    private final HelpContentStore content;
    private final MerchantProfiles merchants;

    @Override
    public List<Topic> topics(String merchantId, Locale locale) {
        return content.topics(portal(merchantId), lang(locale));
    }

    @Override
    public List<ArticleSummary> articles(
            String merchantId, @Nullable String query, @Nullable String topic, Locale locale) {
        var portal = portal(merchantId);
        if (query != null && !query.isBlank()) {
            return content.search(portal, lang(locale), query.strip(), SEARCH_LIMIT);
        }
        if (topic != null && !topic.isBlank()) {
            return content.inTopic(portal, lang(locale), topic);
        }
        return content.suggested(portal, lang(locale));
    }

    @Override
    public Article article(String merchantId, String slug, Locale locale) {
        return content.article(portal(merchantId), lang(locale), slug).orElseThrow(() -> new NotFound("article", slug));
    }

    @Override
    public List<StatusComponent> status(Locale locale) {
        return content.status(lang(locale));
    }

    private Portal portal(String merchantId) {
        return merchants
                .profile(merchantId)
                .map(p -> Portal.ofMerchantType(p.type()))
                .orElseThrow(() -> new NotFound("merchant", merchantId));
    }
}
