package ca.northline.trust.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.messaging.api.MessageTexts;
import ca.northline.messaging.api.TrustWarningNotice;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.MerchantScope;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.trust.api.FlagDecided;
import ca.northline.trust.api.ListingFlags;
import ca.northline.trust.api.ListingTexts;
import ca.northline.trust.application.TrustFlagStore.StoredFlag;
import ca.northline.trust.application.TrustRules.FlagAction;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link TrustFlagQueue} over {@code trust.flags}; every decision is audited ({@code trust.flag_decided}). */
@Service
@RequiredArgsConstructor
class TrustFlagQueueService implements TrustFlagQueue, ListingFlags {

    static final String LISTING = "listing";

    private final TrustFlagStore flags;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final BusinessNames names;
    private final MerchantPlaces places;
    private final ReviewModeration moderation;
    private final ListingTexts listings;
    private final MessageTexts messages;
    private final ReviewStore reviews;
    private final AiScreeningStore screenings;

    @Override
    @Transactional(readOnly = true)
    public List<FlagView> list(@Nullable String state, @Nullable String source, int limit) {
        // S-158: with the business's name, for the AI screening queue
        var nameById = new HashMap<String, String>();
        return flags.list(state, source, Math.clamp(limit, 1, 200)).stream()
                .map(f -> {
                    var m = f.merchantId();
                    return m == null
                            ? view(f)
                            : view(
                                    f,
                                    nameById.computeIfAbsent(
                                            m, id -> names.displayName(id).orElse(id)),
                                    null);
                })
                .toList();
    }

    @Override
    @Transactional
    public FlagView decide(String flagId, String decision, String staffId, String role, @Nullable String note) {
        if (!"dismissed".equals(decision) && !"actioned".equals(decision)) {
            throw RuleViolation.of("decision", "invalid", DECISION_REQUIRED);
        }
        var flag = flags.find(flagId).orElseThrow(() -> new NotFound("flag", flagId));
        if (!record(flag, decision, decision, staffId, role, note)) {
            throw new Conflict("flag_decided", NOT_OPEN);
        }
        return view(flags.find(flagId).orElseThrow(() -> new NotFound("flag", flagId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FlagView> queue(MerchantScope scope, int limit) {
        var nameById = new HashMap<String, String>();
        var provinceById = new HashMap<String, Optional<String>>();
        return flags.queue(scope, clock.instant().minus(Duration.ofDays(7)), Math.clamp(limit, 1, 500)).stream()
                .map(f -> {
                    var m = f.merchantId();
                    if (m == null) {
                        return view(f, null, null);
                    }
                    var name = nameById.computeIfAbsent(
                            m, id -> names.displayName(id).orElse(id));
                    var province = provinceById.computeIfAbsent(m, id -> {
                        var place = places.of(id);
                        return place.ownProvince() ? Optional.ofNullable(place.province()) : Optional.empty();
                    });
                    return view(f, name, province.orElse(null));
                })
                .toList();
    }

    @Override
    @Transactional
    public FlagView act(String flagId, FlagAction action, String staffId, String role, @Nullable String note) {
        var flag = flags.find(flagId).orElseThrow(() -> new NotFound("flag", flagId));
        if (action == FlagAction.WARN && flag.merchantId() == null) {
            throw RuleViolation.of("action", "option", NO_BUSINESS);
        }
        if (action == FlagAction.HIDE_REVIEW && !"review".equals(flag.targetType())) {
            throw RuleViolation.of("action", "option", ReviewModeration.NOT_A_REVIEW);
        }
        if (action == FlagAction.HIDE
                && !java.util.Set.of("review", "listing", "message").contains(flag.targetType())) {
            throw RuleViolation.of("action", "option", NOTHING_TO_HIDE);
        }
        if (!record(flag, "actioned", action.code(), staffId, role, note)) {
            throw new Conflict("flag_decided", NOT_OPEN);
        }
        if (action == FlagAction.HIDE_REVIEW || (action == FlagAction.HIDE && "review".equals(flag.targetType()))) {
            moderation.hide(flag.targetId(), flag.rule(), staffId, role, note);
        } else if (action == FlagAction.HIDE && LISTING.equals(flag.targetType())) {
            listings.takeDown(flag.targetId(), takeDownReason(flag), note, staffId, role);
        } else if (action == FlagAction.HIDE && "message".equals(flag.targetType())) {
            messages.hide(flag.targetId(), staffId, clock.instant());
        }
        if (action == FlagAction.WARN) {
            events.publishEvent(new TrustWarningNotice(
                    Ids.next(),
                    clock.instant(),
                    flagId,
                    java.util.Objects.requireNonNull(flag.merchantId()),
                    flag.rule(),
                    note == null || note.isBlank() ? null : note.strip()));
        }
        return view(flags.find(flagId).orElseThrow(() -> new NotFound("flag", flagId)));
    }

    /** The screening's category as the vetting's rejection reason (shown to the business). */
    static String takeDownReason(StoredFlag flag) {
        var categories = List.of(flag.evidence().getOrDefault("categories", "").split(","));
        if (categories.contains("prohibited")) {
            return "prohibited";
        }
        if (categories.contains("misleading") || categories.contains("scam")) {
            return "misleading";
        }
        return "other";
    }

    @Override
    @Transactional
    public FlagContent content(String flagId, String staffId, String role) {
        var flag = flags.find(flagId).orElseThrow(() -> new NotFound("flag", flagId));
        var content = switch (flag.targetType()) {
            case LISTING ->
                listings.find(flag.targetId())
                        .map(l -> new FlagContent(
                                flagId,
                                LISTING,
                                l.listingId(),
                                l.name(),
                                l.details(),
                                l.kind() + (l.category() == null ? "" : " · " + l.category())));
            case "review" ->
                flag.merchantId() == null
                        ? Optional.<FlagContent>empty()
                        : reviews.find(flag.merchantId(), flag.targetId())
                                .map(r -> new FlagContent(
                                        flagId,
                                        "review",
                                        flag.targetId(),
                                        null,
                                        java.util.Objects.requireNonNullElse(r.text(), ""),
                                        r.rating() + "/5"));
            case "message" ->
                messages.find(flag.targetId())
                        .map(m -> new FlagContent(
                                flagId, "message", m.messageId(), null, m.body(), "from the " + m.senderRole()));
            default -> Optional.<FlagContent>empty();
        };
        var found = content.orElseThrow(() -> new NotFound("flagged item", flag.targetId()));
        audit.record(new AuditTrail.Entry(
                flag.merchantId(),
                staffId,
                role,
                "trust.flag_content_viewed",
                "flag",
                flagId,
                null,
                Map.of("targetType", flag.targetType())));
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScanView> scans(int limit) {
        return screenings.recentScans(Math.clamp(limit, 1, 100)).stream()
                .map(s -> new ScanView(
                        s.id(),
                        s.market(),
                        s.weekStart(),
                        s.merchants(),
                        s.flagsRaised(),
                        s.summary(),
                        s.model() != null,
                        s.ranAt(),
                        flags.ofScan(s.id()).stream()
                                .map(f -> {
                                    var m = f.merchantId();
                                    return view(
                                            f,
                                            m == null
                                                    ? null
                                                    : names.displayName(m).orElse(m),
                                            null);
                                })
                                .toList()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ListingFlag> open(int limit) {
        return flags.openOn(LISTING, null, Math.clamp(limit, 1, 500)).stream()
                .map(f -> {
                    var v = view(f);
                    return new ListingFlag(
                            f.id(),
                            f.targetId(),
                            f.merchantId(),
                            f.rule(),
                            v.source(),
                            v.explanation(),
                            v.categories(),
                            f.createdAt());
                })
                .toList();
    }

    @Override
    @Transactional
    public int resolve(String listingId, boolean actioned, String staffId, String role, @Nullable String note) {
        var decided = 0;
        for (var flag : flags.openOn(LISTING, listingId, 100)) {
            var decision = actioned ? "actioned" : "dismissed";
            if (record(flag, decision, decision, staffId, role, note)) {
                decided++;
            }
        }
        return decided;
    }

    /** Decides one open flag: the row, the audit entry and {@link FlagDecided}. False when it was no longer open. */
    private boolean record(
            StoredFlag flag, String decision, String action, String staffId, String role, @Nullable String note) {
        var flagId = flag.id();
        var cleanNote = note == null || note.isBlank() ? null : note.strip();
        var now = clock.instant();
        if (!flags.decide(flagId, decision, action, staffId, cleanNote, now)) {
            return false;
        }
        // Platform-level entry (no merchant_id): staff moderation isn't listed in the business's own audit log.
        audit.record(new AuditTrail.Entry(
                null,
                staffId,
                role,
                "trust.flag_decided",
                "trust_flag",
                flagId,
                Map.of("state", flag.state()),
                Map.of(
                        "state",
                        decision,
                        "rule",
                        flag.rule(),
                        "source",
                        source(flag),
                        "merchantId",
                        String.valueOf(flag.merchantId()),
                        "action",
                        action)));
        events.publishEvent(new FlagDecided(
                Ids.next(),
                now,
                flagId,
                flag.targetType(),
                flag.targetId(),
                flag.rule(),
                flag.merchantId(),
                decision,
                staffId,
                role,
                cleanNote,
                action));
        return true;
    }

    static FlagView view(StoredFlag f) {
        return view(f, null, null);
    }

    static FlagView view(StoredFlag f, @Nullable String businessName, @Nullable String province) {
        var e = f.evidence();
        var categories = e.getOrDefault("categories", e.getOrDefault("signals", ""));
        return new FlagView(
                f.id(),
                f.targetType(),
                f.targetId(),
                f.rule(),
                f.merchantId(),
                f.state(),
                source(f),
                explanation(f),
                categories.isBlank()
                        ? List.of()
                        : Arrays.stream(categories.split(","))
                                .map(String::strip)
                                .toList(),
                e,
                f.createdAt(),
                f.decidedBy(),
                f.decidedAt(),
                f.decisionNote(),
                f.action(),
                businessName,
                province);
    }

    private static String source(StoredFlag f) {
        return "ai".equals(f.evidence().get("source")) ? "ai" : "rules";
    }

    /** Every flag explains itself: the evidence's explanation, else what the rule means. */
    static String explanation(StoredFlag f) {
        var given = f.evidence().get("explanation");
        if (given != null && !given.isBlank()) {
            return given;
        }
        return switch (f.rule()) {
            case "off_platform_payment" ->
                "The message detector found masked contact details or words about paying outside Northline.";
            case "review_screened" ->
                "The review filter masked "
                        + f.evidence()
                                .getOrDefault("categories", "")
                                .replace("personal_info", "personal information")
                                .replace(",", " and ")
                        + " in a customer's review. It is published masked until you decide.";
            case "review_report" ->
                "The business reported this review"
                        + (f.evidence().containsKey("reason")
                                ? " as " + f.evidence().get("reason").replace('_', ' ')
                                : "")
                        + ".";
            default -> "Raised by the " + f.rule().replace('_', ' ') + " rule.";
        };
    }
}
