package ca.northline.merchants.application;

import ca.northline.merchants.application.Documents.ReadDocument;
import ca.northline.merchants.application.StorefrontUseCases.ArrangeSections;
import ca.northline.merchants.application.StorefrontUseCases.CreateStorefront;
import ca.northline.merchants.application.StorefrontUseCases.PublishStorefront;
import ca.northline.merchants.application.StorefrontUseCases.StorefrontView;
import ca.northline.merchants.application.StorefrontUseCases.UpdateStorefront;
import ca.northline.merchants.application.StorefrontUseCases.ViewPublishedStorefront;
import ca.northline.merchants.application.StorefrontUseCases.ViewStorefront;
import ca.northline.merchants.domain.BrandColor;
import ca.northline.merchants.domain.CtaLabel;
import ca.northline.merchants.domain.Document;
import ca.northline.merchants.domain.MerchantStatus;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.merchants.domain.Slug;
import ca.northline.merchants.domain.Storefront;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Page builder: one storefront per merchant, validated edits, publish with {@code storefront.published}. */
@Service
@RequiredArgsConstructor
@Transactional
class StorefrontService
        implements ViewStorefront,
                CreateStorefront,
                UpdateStorefront,
                ArrangeSections,
                PublishStorefront,
                ViewPublishedStorefront,
                StorefrontSync {

    static final String CTA_FIELD = "ctaLabel";
    static final String CTA_OPTION = "Pick one of the labels.";
    static final String LOGO_FIELD = "logoDocumentId";

    private final StorefrontRepository storefronts;
    private final StorefrontViews views;
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final DomainClaims domains;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public StorefrontView view(String merchantId) {
        return toView(load(merchantId));
    }

    @Override
    public StorefrontView create(String merchantId) {
        var merchant = views.merchant(merchantId);
        ensure(merchantId, merchant.getType(), merchant.getDisplayName());
        return view(merchantId);
    }

    @Override
    public void ensure(String merchantId, MerchantType type, String displayName) {
        if (storefronts.findByMerchant(merchantId).isPresent()) {
            return;
        }
        var base = Slug.from(displayName);
        var slug = base;
        for (int n = 2; storefronts.slugTaken(slug, ""); n++) {
            slug = base.numbered(n);
        }
        storefronts.insert(Storefront.create(merchantId, type, slug, clock.instant()));
    }

    @Override
    public void typeChanged(String merchantId, MerchantType type) {
        storefronts.findByMerchant(merchantId).ifPresent(storefront -> {
            storefront.rebuildFor(type, clock.instant());
            storefronts.replaceSections(storefront);
            storefronts.save(storefront);
        });
    }

    @Override
    public StorefrontView update(UpdateStorefront.Command command) {
        var storefront = lock(command.merchantId());
        var now = clock.instant();
        var problems = new ArrayList<Violation>();
        if (command.brandColor() instanceof String color) {
            attempt(problems, () -> storefront.restyle(new BrandColor(color), now));
        }
        if (command.tagline() instanceof String tagline) {
            attempt(problems, () -> storefront.retitle(tagline, now));
        }
        if (command.announcement() instanceof String announcement) {
            attempt(problems, () -> storefront.announce(announcement, now));
        }
        if (command.ctaLabel() instanceof String label) {
            attempt(problems, () -> storefront.relabel(ctaLabel(label), now));
        }
        if (command.slug() instanceof String raw) {
            attempt(problems, () -> {
                var slug = new Slug(raw.strip());
                if (storefronts.slugTaken(slug, storefront.getId())) {
                    throw RuleViolation.of(Slug.FIELD, "unique", Slug.TAKEN);
                }
                storefront.moveTo(slug, now);
            });
        }
        if (command.customDomain() instanceof String raw) {
            attempt(problems, () -> domains.connect(storefront, raw, now));
        }
        if (command.logoDocumentId() instanceof String logoId) {
            attempt(problems, () -> storefront.useLogo(logo(command.merchantId(), logoId), now));
        }
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        storefronts.save(storefront);
        return toView(storefront);
    }

    @Override
    public StorefrontView arrange(String merchantId, List<Storefront.SectionState> sections) {
        var storefront = load(merchantId);
        storefront.arrange(sections, clock.instant());
        storefronts.save(storefront);
        return toView(storefront);
    }

    @Override
    public StorefrontView publish(String merchantId, String actorId) {
        var storefront = lock(merchantId);
        var merchant = views.merchant(merchantId);
        var published = storefront.publish(actorId, merchant.getStatus() == MerchantStatus.ACTIVE, clock.instant());
        storefronts.save(storefront);
        events.publishEvent(published);
        return toView(storefront);
    }

    @Override
    @Transactional(readOnly = true)
    public StorefrontView bySlug(String slug) {
        var storefront = storefronts
                .findBySlug(slug)
                .filter(s -> s.getPublishedAt() != null)
                .orElseThrow(() -> new NotFound("storefront", slug));
        var view = toView(storefront);
        if (view.merchant().getStatus() != MerchantStatus.ACTIVE) {
            throw new NotFound("storefront", slug);
        }
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public ReadDocument.Content logo(String slug) {
        var view = bySlug(slug);
        var logo = view.logo();
        if (logo == null) {
            throw new NotFound("logo", slug);
        }
        return new ReadDocument.Content(logo, storage.get(logo.storageKey()));
    }

    private StorefrontView toView(Storefront storefront) {
        return views.of(storefront);
    }

    private @Nullable String logo(String merchantId, String documentId) {
        if (documentId.isBlank()) {
            return null;
        }
        return documents
                .find(merchantId, documentId)
                .filter(d -> d.purpose() == Document.Purpose.LOGO)
                .map(Document::id)
                .orElseThrow(() -> RuleViolation.of(LOGO_FIELD, "required", Documents.LOGO_TYPE));
    }

    private static CtaLabel ctaLabel(String code) {
        try {
            return CodedEnum.fromCode(CtaLabel.class, code);
        } catch (IllegalArgumentException _) {
            throw RuleViolation.of(CTA_FIELD, "enum", CTA_OPTION);
        }
    }

    private Storefront load(String merchantId) {
        return storefronts.findByMerchant(merchantId).orElseThrow(() -> new NotFound("storefront", merchantId));
    }

    private Storefront lock(String merchantId) {
        return storefronts.lockByMerchant(merchantId).orElseThrow(() -> new NotFound("storefront", merchantId));
    }

    private static void attempt(List<Violation> problems, Runnable change) {
        try {
            change.run();
        } catch (RuleViolation ex) {
            problems.addAll(ex.getViolations());
        }
    }
}
