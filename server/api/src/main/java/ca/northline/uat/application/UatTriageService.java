package ca.northline.uat.application;

import static ca.northline.uat.domain.FeedbackRules.ALREADY_PARTICIPANT;
import static ca.northline.uat.domain.FeedbackRules.BLOCKED_NEEDS_ITEMS;
import static ca.northline.uat.domain.FeedbackRules.BLOCKING_REQUIRED;
import static ca.northline.uat.domain.FeedbackRules.BLOCKING_UNKNOWN;
import static ca.northline.uat.domain.FeedbackRules.DUPLICATE_REQUIRED;
import static ca.northline.uat.domain.FeedbackRules.DUPLICATE_SELF;
import static ca.northline.uat.domain.FeedbackRules.LABEL_REQUIRED;
import static ca.northline.uat.domain.FeedbackRules.MOVE_NOT_ALLOWED;
import static ca.northline.uat.domain.FeedbackRules.OWNER_NOT_STAFF;
import static ca.northline.uat.domain.FeedbackRules.PARTICIPANT_INACTIVE;
import static ca.northline.uat.domain.FeedbackRules.PILOT_BUSINESS;
import static ca.northline.uat.domain.FeedbackRules.PILOT_BUSINESS_LEAVES;
import static ca.northline.uat.domain.FeedbackRules.SCRIPT_PERSONA;
import static ca.northline.uat.domain.FeedbackRules.SCRIPT_REQUIRED;
import static ca.northline.uat.domain.FeedbackRules.TRACKER_FORMAT;
import static ca.northline.uat.domain.FeedbackRules.WHO_REQUIRED;
import static ca.northline.uat.domain.FeedbackRules.WHO_UNKNOWN;

import ca.northline.developer.api.AuditTrail;
import ca.northline.identity.api.PrivacyAccounts;
import ca.northline.identity.api.StaffDirectory;
import ca.northline.shared.Bytes;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.StaffRole;
import ca.northline.uat.application.UatStore.Feedback;
import ca.northline.uat.application.UatStore.HistoryEntry;
import ca.northline.uat.application.UatStore.Participant;
import ca.northline.uat.application.UatStore.Script;
import ca.northline.uat.application.UatStore.Signoff;
import ca.northline.uat.domain.FeedbackRules;
import ca.northline.uat.domain.FeedbackState;
import ca.northline.uat.domain.Persona;
import ca.northline.uat.domain.SignoffOutcome;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link UatTriage}: the console's UAT screen. */
@Service
@RequiredArgsConstructor
class UatTriageService implements UatTriage {

    static final int QUEUE_LIMIT = 500;
    static final Set<FeedbackState> OPEN =
            EnumSet.of(FeedbackState.NEW, FeedbackState.TRIAGED, FeedbackState.ACCEPTED, FeedbackState.FIXED);

    private final UatStore store;
    private final ScreenshotStorage storage;
    private final AuditTrail audit;
    private final StaffDirectory staff;
    private final PrivacyAccounts accounts;
    private final ParticipantDirectory directory;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<QueueItem> queue(Filter filter) {
        var items = select(filter);
        var names = staffNames();
        var dupes = store.duplicateCounts(items.stream().map(Feedback::id).toList());
        return items.stream().map(f -> item(f, names, dupes)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String csv(Filter filter, Locale locale) {
        var fr = Words.french(locale);
        var out = new StringBuilder(Csv.row(
                fr
                        ? List.of(
                                "Référence",
                                "État",
                                "Bloquant",
                                "Catégorie",
                                "Gravité (participant)",
                                "Profil",
                                "Participant",
                                "Application",
                                "Écran",
                                "Version",
                                "Langue",
                                "Appareil",
                                "Commentaire",
                                "Responsable",
                                "Billet",
                                "Doublon de",
                                "Doublons",
                                "Capture",
                                "Reçu le",
                                "Mis à jour")
                        : List.of(
                                "Reference",
                                "State",
                                "Blocking",
                                "Category",
                                "Severity (participant)",
                                "Persona",
                                "Participant",
                                "App",
                                "Screen",
                                "Version",
                                "Language",
                                "Device",
                                "Feedback",
                                "Owner",
                                "Tracker issue",
                                "Duplicate of",
                                "Duplicates",
                                "Screenshot",
                                "Received",
                                "Updated")));
        var items = select(filter);
        var names = staffNames();
        var dupes = store.duplicateCounts(items.stream().map(Feedback::id).toList());
        var refs = items.stream().collect(Collectors.toMap(Feedback::id, f -> FeedbackRules.reference(f.number())));
        for (var f : items) {
            out.append(Csv.row(List.of(
                    FeedbackRules.reference(f.number()),
                    Words.of(f.state().code(), locale),
                    f.blocking() == null ? "" : Words.bool(f.blocks(), locale),
                    Words.of(f.category().code(), locale),
                    Words.of(f.severity().code(), locale),
                    Words.of(f.persona().code(), locale),
                    f.participantLabel(),
                    f.app().code(),
                    f.route(),
                    f.appVersion(),
                    f.locale(),
                    f.platform(),
                    f.body(),
                    f.ownerId() == null ? "" : names.getOrDefault(f.ownerId(), f.ownerId()),
                    Objects.requireNonNullElse(f.trackerUrl(), ""),
                    f.duplicateOf() == null
                            ? ""
                            : refs.getOrDefault(f.duplicateOf(), duplicateReference(f.duplicateOf())),
                    String.valueOf(dupes.getOrDefault(f.id(), 0)),
                    Words.bool(f.screenshotKey() != null, locale),
                    f.createdAt().toString(),
                    f.updatedAt().toString())));
        }
        return out.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackDetail detail(String id) {
        return detail(store.feedback(id).orElseThrow(() -> new NotFound("uat_feedback", id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Screenshot> screenshot(String id) {
        var f = store.feedback(id).orElseThrow(() -> new NotFound("uat_feedback", id));
        var key = f.screenshotKey();
        var type = f.screenshotType();
        if (key == null || type == null) {
            return Optional.empty();
        }
        return storage.get(key).map(bytes -> new Screenshot(Bytes.of(bytes), type));
    }

    @Override
    @Transactional
    public FeedbackDetail move(String id, Move move, Actor actor) {
        var f = store.lock(id).orElseThrow(() -> new NotFound("uat_feedback", id));
        if (!f.state().canMoveTo(move.to())) {
            throw new Conflict("move_not_allowed", MOVE_NOT_ALLOWED);
        }
        var note = move.note() == null || move.note().isBlank() ? null : FeedbackRules.text(move.note());
        if (note != null && note.length() > FeedbackRules.NOTE_MAX) {
            throw RuleViolation.of("note", "length", FeedbackRules.NOTE_TOO_LONG);
        }
        Boolean blocking = f.blocking();
        String duplicateOf = null;
        switch (move.to()) {
            case ACCEPTED -> {
                if (move.blocking() == null) {
                    throw RuleViolation.of("blocking", "required", BLOCKING_REQUIRED);
                }
                blocking = move.blocking();
            }
            case DUPLICATE -> duplicateOf = root(f, move.duplicateOf());
            default -> {
                // fixed / verified / closed keep the blocking decision; triaged, won't fix and reopen leave it as is
            }
        }
        var moved = f.withState(move.to())
                .withBlocking(blocking)
                .withDuplicateOf(duplicateOf)
                .withUpdatedAt(clock.instant());
        save(moved);
        if (duplicateOf != null) {
            // merge: the duplicates of this one now point at the root too
            for (var child : store.duplicatesOf(f.id())) {
                save(child.withDuplicateOf(duplicateOf).withUpdatedAt(clock.instant()));
            }
        }
        store.append(new HistoryEntry(
                Ids.next(), id, f.state(), move.to(), blocking, actor.userId(), note, clock.instant()));
        var before = new LinkedHashMap<String, @Nullable Object>();
        before.put("state", f.state().code());
        before.put("blocking", f.blocking());
        var after = new LinkedHashMap<String, @Nullable Object>();
        after.put("state", move.to().code());
        after.put("blocking", blocking);
        after.put("duplicateOf", duplicateOf);
        audit.record(new AuditTrail.Entry(
                null, actor.userId(), actor.role(), "uat.feedback_moved", "uat_feedback", id, before, after));
        return detail(store.feedback(id).orElseThrow());
    }

    @Override
    @Transactional
    public FeedbackDetail assign(String id, @Nullable String ownerId, Actor actor) {
        var f = store.lock(id).orElseThrow(() -> new NotFound("uat_feedback", id));
        if (ownerId != null && owners().stream().noneMatch(o -> o.id().equals(ownerId))) {
            throw RuleViolation.of("ownerId", "allowed", OWNER_NOT_STAFF);
        }
        save(f.withOwnerId(ownerId).withUpdatedAt(clock.instant()));
        audit.record(new AuditTrail.Entry(
                        null, actor.userId(), actor.role(), "uat.feedback_assigned", "uat_feedback", id, null, null)
                .withChange(nullable("ownerId", f.ownerId()), nullable("ownerId", ownerId)));
        return detail(store.feedback(id).orElseThrow());
    }

    @Override
    @Transactional
    public FeedbackDetail link(String id, @Nullable String trackerUrl, Actor actor) {
        var f = store.lock(id).orElseThrow(() -> new NotFound("uat_feedback", id));
        var url = trackerUrl == null || trackerUrl.isBlank() ? null : trackerUrl.strip();
        if (url != null && !webAddress(url)) {
            throw RuleViolation.of("trackerUrl", "format", TRACKER_FORMAT);
        }
        save(f.withTrackerUrl(url).withUpdatedAt(clock.instant()));
        audit.record(new AuditTrail.Entry(
                        null, actor.userId(), actor.role(), "uat.feedback_linked", "uat_feedback", id, null, null)
                .withChange(nullable("trackerUrl", f.trackerUrl()), nullable("trackerUrl", url)));
        return detail(store.feedback(id).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Owner> owners() {
        return staff.members().stream()
                .filter(m -> StaffRole.held(m.roles()).stream().anyMatch(r -> r.opens(ConsoleScreen.UAT)))
                .map(m -> new Owner(m.id(), m.name()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ParticipantView> participants(Locale locale) {
        var scripts = store.scripts();
        var latest = latestByParticipant();
        var counts = feedbackCounts();
        var names = staffNames();
        var refs = references();
        // a provider-and-seller pilot business is one participant with two personas (two scripts)
        var groups = directory.all().stream()
                .sorted(Comparator.comparing(Participant::active)
                        .reversed()
                        .thenComparing(Participant::persona)
                        .thenComparing(Participant::label))
                .collect(Collectors.groupingBy(Participant::id, LinkedHashMap::new, Collectors.toList()));
        return groups.values().stream()
                .map(g -> view(g, scripts, latest, counts, names, refs, locale))
                .toList();
    }

    @Override
    @Transactional
    public ParticipantView addParticipant(NewParticipant n, Actor actor, Locale locale) {
        var persona = persona(n.persona());
        var label = n.label().strip();
        if (label.isEmpty() || label.length() > FeedbackRules.LABEL_MAX) {
            throw RuleViolation.of("label", "length", LABEL_REQUIRED);
        }
        if (persona.business()) {
            // S-120: pilot businesses are the cohort on the Pilot onboarding screen; they take part from there
            throw RuleViolation.of("persona", "allowed", PILOT_BUSINESS);
        }
        if (n.contact() == null || n.contact().isBlank()) {
            throw RuleViolation.of("contact", "required", WHO_REQUIRED);
        }
        var userId = accounts.find(n.contact().strip())
                .orElseThrow(() -> RuleViolation.of("contact", "allowed", WHO_UNKNOWN));
        var participant =
                new Participant(Ids.next(), userId, null, persona, label, true, actor.userId(), clock.instant());
        if (!store.insert(participant)) {
            throw new Conflict("already_participant", ALREADY_PARTICIPANT);
        }
        audit.record(new AuditTrail.Entry(
                        null,
                        actor.userId(),
                        actor.role(),
                        "uat.participant_added",
                        "uat_participant",
                        participant.id(),
                        null,
                        null)
                .withChange(null, Map.of("persona", persona.code())));
        return participantView(participant.id(), locale);
    }

    @Override
    @Transactional
    public ParticipantView deactivate(String participantId, Actor actor, Locale locale) {
        var p = store.participant(participantId)
                .orElseThrow(() -> directory.byId(participantId).isEmpty()
                        ? new NotFound("uat_participant", participantId)
                        : new Conflict("pilot_business", PILOT_BUSINESS_LEAVES));
        if (p.active()) {
            store.deactivate(participantId);
            audit.record(new AuditTrail.Entry(
                    p.merchantId(),
                    actor.userId(),
                    actor.role(),
                    "uat.participant_deactivated",
                    "uat_participant",
                    participantId,
                    null,
                    null));
        }
        return participantView(participantId, locale);
    }

    @Override
    @Transactional
    public ParticipantView signoff(String participantId, NewSignoff n, Actor actor, Locale locale) {
        var group = directory.byId(participantId);
        if (group.isEmpty()) {
            throw new NotFound("uat_participant", participantId);
        }
        if (!group.getFirst().active()) {
            throw new Conflict("participant_inactive", PARTICIPANT_INACTIVE);
        }
        var script = store.scripts().stream()
                .filter(s -> s.code().equals(n.script()))
                .findFirst()
                .orElseThrow(() -> RuleViolation.of("script", "allowed", SCRIPT_REQUIRED));
        var p = group.stream()
                .filter(g -> g.persona() == script.persona())
                .findFirst()
                .orElseThrow(() -> RuleViolation.of("script", "allowed", SCRIPT_PERSONA));
        var outcome = Optional.ofNullable(n.outcome())
                .flatMap(o -> EnumSet.allOf(SignoffOutcome.class).stream()
                        .filter(v -> v.code().equals(o))
                        .findFirst())
                .orElseThrow(() -> RuleViolation.of("outcome", "allowed", FeedbackRules.OUTCOME_REQUIRED));
        var comments = n.comments() == null || n.comments().isBlank() ? null : FeedbackRules.text(n.comments());
        if (comments != null && comments.length() > FeedbackRules.COMMENTS_MAX) {
            throw RuleViolation.of("comments", "length", FeedbackRules.COMMENTS_TOO_LONG);
        }
        var blocking = n.blockingIds().stream().distinct().toList();
        for (var id : blocking) {
            if (store.feedback(id).isEmpty()) {
                throw RuleViolation.of("blockingIds", "allowed", BLOCKING_UNKNOWN);
            }
        }
        if (outcome == SignoffOutcome.BLOCKED && blocking.isEmpty() && comments == null) {
            throw RuleViolation.of("blockingIds", "required", BLOCKED_NEEDS_ITEMS);
        }
        var signoff = new Signoff(
                Ids.next(),
                participantId,
                script.code(),
                script.version(),
                outcome,
                comments,
                blocking,
                actor.userId(),
                clock.instant());
        store.insert(signoff);
        audit.record(new AuditTrail.Entry(
                        p.merchantId(),
                        actor.userId(),
                        actor.role(),
                        "uat.signoff_recorded",
                        "uat_participant",
                        participantId,
                        null,
                        null)
                .withChange(
                        null,
                        Map.of(
                                "script",
                                script.code(),
                                "version",
                                script.version(),
                                "outcome",
                                outcome.code(),
                                "blocking",
                                blocking)));
        return participantView(participantId, locale);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScriptView> scripts(Locale locale) {
        return store.scripts().stream().map(s -> scriptView(s, locale)).toList();
    }

    static ScriptView scriptView(Script s, Locale locale) {
        var fr = Words.french(locale);
        var path = fr ? s.docPath().replace("docs/uat/", "docs/uat/fr/") : s.docPath();
        return new ScriptView(
                s.code(),
                s.persona().code(),
                fr ? s.titleFr() : s.titleEn(),
                s.version(),
                path,
                path.replace(".md", "-signoff.md"));
    }

    // --- helpers

    private List<Feedback> select(Filter filter) {
        Set<FeedbackState> states = switch (filter.state()) {
            case "open" -> OPEN;
            case "all" -> Set.of();
            default -> Set.of(CodedEnum.fromCode(FeedbackState.class, filter.state()));
        };
        return named(store.queue(states, filter.blocking(), QUEUE_LIMIT).stream()
                .filter(f -> filter.persona() == null || f.persona().code().equals(filter.persona()))
                .filter(f -> filter.app() == null || f.app().code().equals(filter.app()))
                .toList());
    }

    /** Feedback from a pilot business carries the pilot's working name (S-120's cohort), people's their label. */
    private List<Feedback> named(List<Feedback> items) {
        if (items.stream().allMatch(f -> !f.participantLabel().isEmpty())) {
            return items;
        }
        var labels =
                directory.all().stream().collect(Collectors.toMap(Participant::id, Participant::label, (a, _) -> a));
        return items.stream()
                .map(f -> f.participantLabel().isEmpty()
                        ? f.withParticipantLabel(labels.getOrDefault(f.participantId(), ""))
                        : f)
                .toList();
    }

    /** The item a duplicate points at: never itself, never one of its own duplicates; a duplicate's root instead. */
    private String root(Feedback f, @Nullable String duplicateOf) {
        if (duplicateOf == null || duplicateOf.isBlank()) {
            throw RuleViolation.of("duplicateOf", "required", DUPLICATE_REQUIRED);
        }
        var target = store.feedback(duplicateOf)
                .orElseThrow(() -> RuleViolation.of("duplicateOf", "allowed", DUPLICATE_REQUIRED));
        var seen = new java.util.HashSet<String>();
        while (target.duplicateOf() != null && seen.add(target.id())) {
            var next = target.duplicateOf();
            target = store.feedback(next).orElseThrow(() -> new NotFound("uat_feedback", next));
        }
        if (target.id().equals(f.id())) {
            throw RuleViolation.of("duplicateOf", "allowed", DUPLICATE_SELF);
        }
        return target.id();
    }

    private void save(Feedback f) {
        if (!store.save(f)) {
            throw new Conflict("stale", FeedbackRules.STALE);
        }
    }

    private FeedbackDetail detail(Feedback raw) {
        var f = named(List.of(raw)).getFirst();
        var names = staffNames();
        var duplicates = named(store.duplicatesOf(f.id()));
        var dupes = store.duplicateCounts(List.of(f.id()));
        var dupOf = f.duplicateOf() == null
                ? Optional.<Feedback>empty()
                : store.feedback(f.duplicateOf()).map(d -> named(List.of(d)).getFirst());
        return new FeedbackDetail(
                item(f, names, dupes),
                f.body(),
                f.appVersion(),
                f.locale(),
                f.platform(),
                f.merchantId(),
                f.state().next().stream().map(FeedbackState::code).toList(),
                duplicates.stream().map(d -> item(d, names, Map.of())).toList(),
                dupOf.map(d -> item(d, names, Map.of())).orElse(null),
                store.history(f.id()).stream()
                        .map(h -> new TriageStep(
                                h.from() == null ? null : h.from().code(),
                                h.to().code(),
                                h.blocking(),
                                h.actorId(),
                                names.get(h.actorId()),
                                h.note(),
                                h.at()))
                        .toList());
    }

    static QueueItem item(Feedback f, Map<String, String> names, Map<String, Integer> dupes) {
        return new QueueItem(
                f.id(),
                FeedbackRules.reference(f.number()),
                f.state().code(),
                f.blocking(),
                f.category().code(),
                f.severity().code(),
                f.persona().code(),
                f.participantLabel(),
                f.app().code(),
                summary(f.body()),
                f.route(),
                f.ownerId(),
                f.ownerId() == null ? null : names.get(f.ownerId()),
                f.trackerUrl(),
                f.duplicateOf(),
                dupes.getOrDefault(f.id(), 0),
                f.screenshotKey() != null,
                f.createdAt(),
                f.updatedAt());
    }

    /** The first line, at most 120 characters. */
    static String summary(String body) {
        var line = body.lines().findFirst().orElse("").strip();
        return line.length() > 120 ? line.substring(0, 119) + "…" : line;
    }

    private Map<String, String> staffNames() {
        return staff.members().stream()
                .collect(Collectors.toMap(StaffDirectory.Member::id, StaffDirectory.Member::name, (a, _) -> a));
    }

    private String duplicateReference(String id) {
        return store.feedback(id).map(d -> FeedbackRules.reference(d.number())).orElse(id);
    }

    private Map<String, String> references() {
        return store.queue(Set.of(), null, Integer.MAX_VALUE).stream()
                .collect(Collectors.toMap(Feedback::id, f -> FeedbackRules.reference(f.number())));
    }

    private Map<String, Integer> feedbackCounts() {
        var counts = new HashMap<String, Integer>();
        for (var f : store.queue(Set.of(), null, Integer.MAX_VALUE)) {
            counts.merge(f.participantId(), 1, Integer::sum);
        }
        return counts;
    }

    /** participant id → script code → their latest sign-off. */
    private Map<String, Map<String, Signoff>> latestByParticipant() {
        var out = new HashMap<String, Map<String, Signoff>>();
        for (var s : store.latestSignoffs()) {
            out.computeIfAbsent(s.participantId(), _ -> new HashMap<>()).put(s.scriptCode(), s);
        }
        return out;
    }

    private ParticipantView participantView(String id, Locale locale) {
        var group = directory.byId(id);
        if (group.isEmpty()) {
            throw new NotFound("uat_participant", id);
        }
        return view(
                group, store.scripts(), latestByParticipant(), feedbackCounts(), staffNames(), references(), locale);
    }

    private ParticipantView view(
            List<Participant> group,
            List<Script> scripts,
            Map<String, Map<String, Signoff>> latest,
            Map<String, Integer> counts,
            Map<String, String> names,
            Map<String, String> refs,
            Locale locale) {
        var p = group.getFirst();
        var personas = group.stream().map(Participant::persona).collect(Collectors.toSet());
        var history = store.signoffsOf(p.id()).stream()
                .collect(Collectors.groupingBy(Signoff::scriptCode, Collectors.counting()));
        var own = latest.getOrDefault(p.id(), Map.of());
        var signoffs = scripts.stream()
                .filter(s -> personas.contains(s.persona()))
                .map(s -> {
                    var view = scriptView(s, locale);
                    var last = own.get(s.code());
                    return last == null
                            ? new SignoffView(
                                    s.code(), view.title(), s.version(), "pending", null, List.of(), null, null, 0)
                            : new SignoffView(
                                    s.code(),
                                    view.title(),
                                    last.scriptVersion(),
                                    last.outcome().code(),
                                    last.comments(),
                                    last.blockingIds().stream()
                                            .map(b -> refs.getOrDefault(b, b))
                                            .toList(),
                                    names.get(last.recordedBy()),
                                    last.recordedAt(),
                                    history.getOrDefault(s.code(), 0L).intValue());
                })
                .toList();
        return new ParticipantView(
                p.id(),
                p.persona().code(),
                p.label(),
                p.merchantId() == null ? "user" : "business",
                p.merchantId(),
                p.active(),
                p.createdAt(),
                counts.getOrDefault(p.id(), 0),
                signoffs);
    }

    private static Persona persona(@Nullable String code) {
        return EnumSet.allOf(Persona.class).stream()
                .filter(p -> p.code().equals(code))
                .findFirst()
                .orElseThrow(() -> RuleViolation.of("persona", "allowed", FeedbackRules.PERSONA_REQUIRED));
    }

    static boolean webAddress(String url) {
        if (url.length() > FeedbackRules.TRACKER_MAX) {
            return false;
        }
        try {
            var uri = new URI(url);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (URISyntaxException bad) {
            return false;
        }
    }

    private static Map<String, @Nullable Object> nullable(String key, @Nullable Object value) {
        var map = new HashMap<String, @Nullable Object>();
        map.put(key, value);
        return map;
    }
}
