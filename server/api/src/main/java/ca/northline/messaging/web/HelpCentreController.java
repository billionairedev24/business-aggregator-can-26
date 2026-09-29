package ca.northline.messaging.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.messaging.api.CaseReferences.Reference;
import ca.northline.messaging.application.BrowseHelp;
import ca.northline.messaging.application.BrowseHelp.Article;
import ca.northline.messaging.application.BrowseHelp.ArticleSummary;
import ca.northline.messaging.application.BrowseHelp.StatusComponent;
import ca.northline.messaging.application.BrowseHelp.Topic;
import ca.northline.messaging.application.ManageHelpCases;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.RequiresMerchant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Help centre reads: {@code GET /help/topics}, {@code /help/articles?q=&topic=}, {@code /help/articles/{slug}},
 * {@code /help/status}, and the case form's {@code /help/related} list. Content follows {@code Accept-Language}.
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/help")
@RequiredArgsConstructor
class HelpCentreController {

    private final BrowseHelp help;
    private final ManageHelpCases cases;

    @GetMapping("/topics")
    @RequiresMerchant(VIEW)
    ListResponse<Topic> topics(@PathVariable String merchantId, Locale locale) {
        return new ListResponse<>(help.topics(merchantId, locale));
    }

    @GetMapping("/articles")
    @RequiresMerchant(VIEW)
    ListResponse<ArticleSummary> articles(
            @PathVariable String merchantId,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable String topic,
            Locale locale) {
        return new ListResponse<>(help.articles(merchantId, q, topic, locale));
    }

    @GetMapping("/articles/{slug}")
    @RequiresMerchant(VIEW)
    Article article(@PathVariable String merchantId, @PathVariable String slug, Locale locale) {
        return help.article(merchantId, slug, locale);
    }

    @GetMapping("/status")
    @RequiresMerchant(VIEW)
    ListResponse<StatusComponent> status(@PathVariable String merchantId, Locale locale) {
        return new ListResponse<>(help.status(locale));
    }

    @GetMapping("/related")
    @RequiresMerchant(VIEW)
    ListResponse<Reference> related(@PathVariable String merchantId, Locale locale) {
        return new ListResponse<>(cases.related(merchantId, locale));
    }
}
