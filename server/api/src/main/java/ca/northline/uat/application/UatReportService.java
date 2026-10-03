package ca.northline.uat.application;

import ca.northline.identity.api.StaffDirectory;
import ca.northline.region.api.Regions;
import ca.northline.uat.api.UatReadiness;
import ca.northline.uat.application.UatStore.Feedback;
import ca.northline.uat.application.UatStore.HistoryEntry;
import ca.northline.uat.domain.FeedbackRules;
import ca.northline.uat.domain.FeedbackState;
import ca.northline.uat.domain.GoNoGo;
import ca.northline.uat.domain.Persona;
import ca.northline.uat.domain.Severity;
import ca.northline.uat.domain.SignoffOutcome;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link UatReadiness}: the go/no-go report, its CSV, and the trend. */
@Service
@RequiredArgsConstructor
class UatReportService implements UatReports {

    static final int TREND_DAYS = 14;
    private static final Set<FeedbackState> RESOLVED =
            Set.of(FeedbackState.VERIFIED, FeedbackState.CLOSED, FeedbackState.WONT_FIX, FeedbackState.DUPLICATE);

    private final UatStore store;
    private final StaffDirectory staff;
    private final Regions regions;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public GoNoGoReport report(Locale locale) {
        var all = store.queue(Set.of(), null, Integer.MAX_VALUE);
        var names = staff.members().stream()
                .collect(Collectors.toMap(StaffDirectory.Member::id, StaffDirectory.Member::name, (a, _) -> a));
        var dupes = store.duplicateCounts(all.stream().map(Feedback::id).toList());

        var open = all.stream()
                .filter(f -> f.blocks() && f.state() == FeedbackState.ACCEPTED)
                .toList();
        var unverified = all.stream()
                .filter(f -> f.blocks() && f.state() == FeedbackState.FIXED)
                .toList();
        var untriaged = all.stream()
                .filter(f -> f.state().untriaged() && f.severity() == Severity.BLOCKER)
                .toList();
        var items = new ArrayList<BlockingItem>();
        for (var f : concat(open, unverified, untriaged)) {
            items.add(new BlockingItem(
                    f.id(),
                    FeedbackRules.reference(f.number()),
                    f.state().code(),
                    f.persona().code(),
                    f.app().code(),
                    UatTriageService.summary(f.body()),
                    f.ownerId() == null ? null : names.get(f.ownerId()),
                    f.trackerUrl(),
                    f.createdAt(),
                    1 + dupes.getOrDefault(f.id(), 0)));
        }

        var coverage = coverage();
        var verdict = GoNoGo.decide(open.size(), unverified.size(), untriaged.size(), coverage);
        var scripts = store.scripts().stream()
                .collect(Collectors.toMap(UatStore.Script::persona, s -> UatTriageService.scriptView(s, locale)));
        return new GoNoGoReport(
                clock.instant(),
                verdict.go() ? "go" : "no_go",
                verdict.reasons().stream()
                        .map(r -> new GoNoGoReason(
                                r.code().code(),
                                r.count(),
                                r.persona() == null ? null : r.persona().code(),
                                Words.reason(
                                        r.code().code(),
                                        r.count(),
                                        r.persona() == null
                                                ? ""
                                                : Words.of(r.persona().code(), locale),
                                        locale)))
                        .toList(),
                open.size(),
                unverified.size(),
                untriaged.size(),
                items,
                coverage.stream()
                        .map(c -> {
                            var script = scripts.get(c.persona());
                            return new PersonaCoverage(
                                    c.persona().code(),
                                    script == null ? "" : script.code(),
                                    script == null ? "" : script.title(),
                                    script == null ? "" : script.version(),
                                    c.participants(),
                                    c.signedOff(),
                                    c.withComments(),
                                    c.blocked(),
                                    c.pending(),
                                    c.complete());
                        })
                        .toList(),
                trend(regions.platformZone()));
    }

    @Override
    @Transactional(readOnly = true)
    public String csv(Locale locale) {
        var r = report(locale);
        var fr = Words.french(locale);
        var out = new StringBuilder();
        out.append(Csv.row(List.of(
                fr ? "Décision" : "Verdict",
                Words.of(r.verdict(), locale),
                r.generatedAt().toString())));
        for (var reason : r.reasons()) {
            out.append(Csv.row(List.of(fr ? "Raison" : "Reason", reason.text())));
        }
        out.append("\r\n");
        out.append(Csv.row(
                fr
                        ? List.of(
                                "Élément bloquant",
                                "État",
                                "Profil",
                                "Application",
                                "Résumé",
                                "Responsable",
                                "Billet",
                                "Signalé le",
                                "Signalements")
                        : List.of(
                                "Blocking item",
                                "State",
                                "Persona",
                                "App",
                                "Summary",
                                "Owner",
                                "Tracker issue",
                                "Reported",
                                "Reports")));
        for (var b : r.blockingItems()) {
            out.append(Csv.row(List.of(
                    b.reference(),
                    Words.of(b.state(), locale),
                    Words.of(b.persona(), locale),
                    b.app(),
                    b.summary(),
                    Objects.requireNonNullElse(b.ownerName(), ""),
                    Objects.requireNonNullElse(b.trackerUrl(), ""),
                    b.reportedAt().toString(),
                    String.valueOf(b.reports()))));
        }
        out.append("\r\n");
        out.append(Csv.row(
                fr
                        ? List.of(
                                "Profil",
                                "Scénario",
                                "Version",
                                "Participants",
                                "Approuvé",
                                "Avec commentaires",
                                "Bloqué",
                                "En attente",
                                "Complet")
                        : List.of(
                                "Persona",
                                "Script",
                                "Version",
                                "Participants",
                                "Signed off",
                                "With comments",
                                "Blocked",
                                "Pending",
                                "Complete")));
        for (var c : r.coverage()) {
            out.append(Csv.row(List.of(
                    Words.of(c.persona(), locale),
                    c.scriptTitle(),
                    c.scriptVersion(),
                    String.valueOf(c.participants()),
                    String.valueOf(c.signedOff()),
                    String.valueOf(c.withComments()),
                    String.valueOf(c.blocked()),
                    String.valueOf(c.pending()),
                    Words.bool(c.complete(), locale))));
        }
        out.append("\r\n");
        out.append(Csv.row(
                fr
                        ? List.of("Jour", "Signalés", "Bloquants ouverts", "Réglés")
                        : List.of("Day", "Reported", "Open blocking", "Resolved")));
        for (var d : r.trend()) {
            out.append(Csv.row(List.of(
                    d.date().toString(),
                    String.valueOf(d.reported()),
                    String.valueOf(d.openBlocking()),
                    String.valueOf(d.resolved()))));
        }
        return out.toString();
    }

    /** Per persona: active participants and their latest sign-off of the persona's script. */
    private List<GoNoGo.Coverage> coverage() {
        var latest = store.latestSignoffs();
        var active = store.participants().stream()
                .filter(UatStore.Participant::active)
                .toList();
        var scripts =
                store.scripts().stream().collect(Collectors.toMap(UatStore.Script::persona, UatStore.Script::code));
        var out = new ArrayList<GoNoGo.Coverage>();
        for (var persona : Persona.values()) {
            var people = active.stream().filter(p -> p.persona() == persona).toList();
            var script = scripts.get(persona);
            var outcomes = people.stream()
                    .flatMap(p -> latest.stream()
                            .filter(s -> s.participantId().equals(p.id())
                                    && s.scriptCode().equals(script))
                            .findFirst()
                            .stream())
                    .collect(Collectors.groupingBy(UatStore.Signoff::outcome, Collectors.counting()));
            var signed = outcomes.getOrDefault(SignoffOutcome.SIGNED_OFF, 0L).intValue();
            var comments = outcomes.getOrDefault(SignoffOutcome.WITH_COMMENTS, 0L).intValue();
            var blocked = outcomes.getOrDefault(SignoffOutcome.BLOCKED, 0L).intValue();
            out.add(new GoNoGo.Coverage(persona, people.size(), signed, comments, blocked));
        }
        return out;
    }

    /** The last {@value #TREND_DAYS} days: replay every triage step to know what was open at each day's end. */
    private List<TrendDay> trend(ZoneId zone) {
        var today = LocalDate.ofInstant(clock.instant(), zone);
        var first = today.minusDays(TREND_DAYS - 1);
        var reported = new HashMap<LocalDate, Integer>();
        for (var at : store.reportedSince(first.atStartOfDay(zone).toInstant())) {
            reported.merge(LocalDate.ofInstant(at, zone), 1, Integer::sum);
        }
        var history = store.allHistory().stream()
                .sorted(Comparator.comparing(HistoryEntry::at))
                .toList();
        var state = new HashMap<String, HistoryEntry>();
        var resolved = new HashMap<LocalDate, Integer>();
        var days = new ArrayList<TrendDay>();
        var i = 0;
        for (var day = first; !day.isAfter(today); day = day.plusDays(1)) {
            var end = day.plusDays(1).atStartOfDay(zone).toInstant();
            while (i < history.size() && history.get(i).at().isBefore(end)) {
                var h = history.get(i++);
                state.put(h.feedbackId(), h);
                if (RESOLVED.contains(h.to())) {
                    resolved.merge(LocalDate.ofInstant(h.at(), zone), 1, Integer::sum);
                }
            }
            var openBlocking = (int) state.values().stream()
                    .filter(h -> Boolean.TRUE.equals(h.blocking())
                            && (h.to() == FeedbackState.ACCEPTED || h.to() == FeedbackState.FIXED))
                    .count();
            days.add(new TrendDay(day, reported.getOrDefault(day, 0), openBlocking, resolved.getOrDefault(day, 0)));
        }
        return days;
    }

    @SafeVarargs
    private static List<Feedback> concat(List<Feedback>... lists) {
        var out = new ArrayList<Feedback>();
        for (var l : lists) {
            out.addAll(l);
        }
        return out;
    }
}
