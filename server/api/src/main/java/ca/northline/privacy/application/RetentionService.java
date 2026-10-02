package ca.northline.privacy.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.identity.api.AccountFacts;
import ca.northline.privacy.application.PrivacyRequests.Actor;
import ca.northline.privacy.application.RetentionCatalogue.Category;
import ca.northline.privacy.application.RetentionRunStore.RunRecord;
import ca.northline.region.api.PrivacyLaw;
import ca.northline.region.api.PrivacyRegimes;
import ca.northline.region.api.Regions;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.privacy.RetentionContributor;
import ca.northline.shared.privacy.RetentionContributor.HeldRef;
import ca.northline.shared.privacy.RetentionContributor.Run;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-107: runs the retention schedule ({@link RetentionCatalogue}) through each module's {@link RetentionContributor}
 * and reports it.
 *
 * <p>A run of a category: collect every module's legal holds (open orders, upcoming bookings, held escrow, disputes and
 * when they were decided), keep those in force for the category (its own hold codes; a decided dispute holds for
 * {@code afterDisputeClosed}, and for the decision minimum of the person's province's law when the category says so),
 * add the people with an open privacy request, then count what is past its period with and without holds (the "held"
 * figure) and — unless it is a dry run — purge in batches, one transaction per batch under the category's advisory
 * lock (another replica on the same category stops). Every run is a {@code privacy.retention_runs} row, an audit log
 * entry ({@code privacy.retention_run}) and metrics. A failing category is recorded and the others still run.
 */
@Slf4j
@Service
class RetentionService implements Retention.Work, Retention.Desk {

    static final String UNKNOWN_CATEGORY = "Choose a category of the retention schedule that has a job.";

    /** A nightly run skips a category that ran this recently (another replica's run of the same night). */
    private static final Duration SAME_NIGHT = Duration.ofHours(20);

    private final RetentionCatalogue catalogue;
    private final RetentionSettings settings;
    private final List<RetentionContributor> contributors;
    private final Map<String, RetentionContributor> byCategory;
    private final RetentionRunStore store;
    private final RetentionMeter meter;
    private final AuditTrail audit;
    private final PrivacyRegimes regimes;
    private final AccountFacts facts;
    private final Regions regions;
    private final Clock clock;
    private final TransactionTemplate tx;

    RetentionService(
            RetentionCatalogue catalogue,
            RetentionSettings settings,
            List<RetentionContributor> contributors,
            RetentionRunStore store,
            RetentionMeter meter,
            AuditTrail audit,
            PrivacyRegimes regimes,
            AccountFacts facts,
            Regions regions,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.catalogue = catalogue;
        this.settings = settings;
        this.contributors = List.copyOf(contributors);
        this.byCategory = wire(catalogue, contributors);
        this.store = store;
        this.meter = meter;
        this.audit = audit;
        this.regimes = regimes;
        this.facts = facts;
        this.regions = regions;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Every category that runs has exactly one contributor, of its module; no contributor runs an unknown category. */
    private static Map<String, RetentionContributor> wire(
            RetentionCatalogue catalogue, List<RetentionContributor> contributors) {
        var map = new HashMap<String, RetentionContributor>();
        for (var contributor : contributors) {
            for (var code : contributor.categories()) {
                var category = catalogue.category(code)
                        .orElseThrow(() -> new IllegalStateException(
                                contributor.module() + " runs " + code + ", which is not in the retention schedule"));
                if (!category.runs() || !category.module().equals(contributor.module())) {
                    throw new IllegalStateException(code + " belongs to " + category.module() + " and has no job");
                }
                if (map.put(code, contributor) != null) {
                    throw new IllegalStateException("Two modules run " + code);
                }
            }
        }
        for (var category : catalogue.runnable()) {
            if (!map.containsKey(category.code())) {
                throw new IllegalStateException(category.code() + " (" + category.module() + ") has no retention job");
            }
        }
        return Map.copyOf(map);
    }

    @Override
    public int runScheduled() {
        var now = clock.instant();
        var due = catalogue.runnable().stream()
                .filter(c -> !store.ranSince(c.code(), now.minus(SAME_NIGHT)))
                .toList();
        if (due.isEmpty()) {
            return 0;
        }
        var context = context(now);
        var actor = new Actor(Intake.SYSTEM, Intake.SYSTEM);
        for (var category : due) {
            run(category, settings.onlyCount(), RunRecord.SCHEDULE, actor, context);
        }
        return due.size();
    }

    @Override
    public List<Retention.RunView> run(Retention.Command command, Actor actor) {
        var code = command.category();
        var categories = code == null
                ? catalogue.runnable()
                : List.of(catalogue.category(code)
                        .filter(Category::runs)
                        .orElseThrow(() -> RuleViolation.of("category", "unknown", UNKNOWN_CATEGORY)));
        var context = context(clock.instant());
        return categories.stream()
                .map(c -> run(c, command.dryRun(), RunRecord.STAFF, actor, context))
                .toList();
    }

    /** What every category of one run shares: the holds of every module and the people with an open request. */
    private record Context(List<HeldRef> holds, Set<String> subjects, Map<String, Integer> lawDays) {}

    private Context context(Instant now) {
        var holds = contributors.stream().flatMap(c -> c.holds(now).stream()).toList();
        var related = contributors.stream().flatMap(c -> c.relate(holds).stream());
        return new Context(
                Stream.concat(holds.stream(), related).toList(), store.openRequestSubjects(), new HashMap<>());
    }

    private Retention.RunView run(Category category, boolean dryRun, String trigger, Actor actor, Context context) {
        var contributor = Objects.requireNonNull(byCategory.get(category.code()));
        var started = clock.instant();
        long affected = 0;
        long held = 0;
        long remaining = 0;
        var outcome = RunRecord.SUCCEEDED;
        @Nullable String error = null;
        try {
            var run = new Run(
                    category.code(),
                    started,
                    minus(started, category.requiredPeriod()),
                    settings.batchSize(),
                    dryRun,
                    held(category, context, started),
                    context.subjects());
            var due = contributor.expired(run);
            held = Math.max(0, contributor.expired(run.ignoringHolds()) - due);
            if (dryRun) {
                affected = due;
                remaining = due;
            } else {
                affected = purge(contributor, run);
                remaining = affected < due ? contributor.expired(run) : 0;
            }
        } catch (RuntimeException e) {
            outcome = RunRecord.FAILED;
            error = e.getClass().getSimpleName();
            log.error("Retention of {} failed; it runs again next time", category.code(), e);
        }
        var record = new RunRecord(
                Ids.next(),
                category.code(),
                category.module(),
                dryRun,
                trigger,
                actor.userId(),
                started,
                clock.instant(),
                outcome,
                affected,
                held,
                remaining,
                error);
        tx.executeWithoutResult(_ -> {
            store.insert(record);
            audit.record(new AuditTrail.Entry(
                    null,
                    actor.userId(),
                    actor.roles(),
                    "privacy.retention_run",
                    "retention_category",
                    category.code(),
                    null,
                    Map.of(
                            "dryRun", dryRun,
                            "trigger", trigger,
                            "outcome", record.outcome(),
                            "affected", record.affected(),
                            "held", record.held(),
                            "remaining", record.remaining())));
        });
        meter.ran(category.code(), dryRun, outcome);
        if (!dryRun && affected > 0) {
            meter.purged(category.code(), category.action().code(), affected);
        }
        if (affected > 0 || held > 0) {
            log.info(
                    "Retention {}{}: {} row(s) {}, {} held, {} left",
                    category.code(),
                    dryRun ? " (dry run)" : "",
                    affected,
                    dryRun ? "due" : category.action().code() + "d",
                    held,
                    remaining);
        }
        return view(record);
    }

    /** Batches, each in its own transaction under the category's lock; stops at the limit or when another has it. */
    private long purge(RetentionContributor contributor, Run run) {
        long total = 0;
        for (var batch = 0; batch < settings.batchLimit(); batch++) {
            var done = Objects.requireNonNull(tx.execute(_ -> store.tryLock(run.category()) ? contributor.purge(run) : -1L));
            if (done < 0) {
                log.info("Retention {}: another replica is running it", run.category());
                break;
            }
            total += done;
            if (done < run.batch()) {
                break;
            }
        }
        return total;
    }

    /** The references still held for this category at {@code now}. */
    private Set<String> held(Category category, Context context, Instant now) {
        var codes = Set.copyOf(category.holdCodes());
        var keys = new TreeSet<String>();
        for (var hold : context.holds()) {
            if (codes.contains(hold.reason().code()) && inForce(category, hold, context, now)) {
                keys.add(hold.ref().key());
            }
        }
        return keys;
    }

    private boolean inForce(Category category, HeldRef hold, Context context, Instant now) {
        var closed = hold.closedAt();
        if (closed == null) {
            return true;
        }
        var after = category.afterDisputeClosed();
        if (after == null) {
            return false;
        }
        var until = plus(closed, after);
        var subject = hold.subjectId();
        if (category.lawMinimum() && subject != null) {
            var days = context.lawDays().computeIfAbsent(subject, this::decisionDays);
            var lawUntil = closed.plus(Duration.ofDays(days));
            until = lawUntil.isAfter(until) ? lawUntil : until;
        }
        return until.isAfter(now);
    }

    /** What the law of the person's province adds after a decision (region.privacy_laws). */
    private int decisionDays(String subjectId) {
        return regimes.forProvince(facts.of(subjectId).defaultProvince()).decisionRetentionDays();
    }

    private Instant minus(Instant at, Period period) {
        return at.atZone(regions.platformZone()).minus(period).toInstant();
    }

    private Instant plus(Instant at, Period period) {
        return at.atZone(regions.platformZone()).plus(period).toInstant();
    }

    @Override
    public Retention.Report report(Locale locale) {
        var now = clock.instant();
        var latest = store.latest();
        var succeeded = store.latestSucceeded();
        var first = store.firstRun();
        var next = settings.on() ? nextRun(now) : null;
        var categories = catalogue.categories().stream()
                .map(c -> {
                    var last = latest.get(c.code());
                    var success = succeeded.get(c.code());
                    var since = success != null ? success.finishedAt() : first.orElse(null);
                    var overdue = c.runs() && since != null && since.isBefore(now.minus(settings.stale()));
                    return new Retention.CategoryView(
                            c.code(),
                            c.module(),
                            c.name().in(locale),
                            c.clause(),
                            c.policy(),
                            c.period() == null ? null : c.period().toString(),
                            c.afterDisputeClosed() == null ? null : c.afterDisputeClosed().toString(),
                            c.lawMinimum(),
                            c.starts().in(locale),
                            c.basis().in(locale),
                            c.action(),
                            c.enforcement(),
                            c.holdCodes(),
                            c.note() == null ? null : c.note().in(locale),
                            last == null ? null : view(last),
                            success == null ? null : success.finishedAt(),
                            success == null ? 0 : success.affected(),
                            last == null ? 0 : last.held(),
                            c.runs() ? next : null,
                            overdue);
                })
                .toList();
        var operational = catalogue.operational().stream()
                .map(o -> new Retention.OperationalView(o.code(), o.name().in(locale), o.period(), o.where(), o.setting()))
                .toList();
        var laws = Arrays.stream(PrivacyLaw.values())
                .map(law -> regimes.of(law, ""))
                .map(r -> new Retention.LawView(r.law().code(), r.shortName(locale), r.decisionRetentionDays()))
                .collect(Collectors.toMap(Retention.LawView::code, v -> v, (a, _) -> a, LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        return new Retention.Report(now, next, settings.onlyCount(), categories, operational, laws);
    }

    private @Nullable Instant nextRun(Instant now) {
        var next = CronExpression.parse(settings.schedule()).next(ZonedDateTime.ofInstant(now, regions.platformZone()));
        return next == null ? null : next.toInstant();
    }

    @Override
    public String csv(Locale locale) {
        var fr = locale.getLanguage().equals("fr");
        var header = fr
                ? List.of(
                        "categorie", "module", "nom", "clause de la politique", "duree", "apres un litige",
                        "minimum de la loi", "depart", "action", "application", "blocages", "derniere execution",
                        "resultat", "essai", "dernier succes", "lignes traitees", "lignes bloquees", "prochaine echeance",
                        "en retard")
                : List.of(
                        "category", "module", "name", "policy clause", "period", "after a dispute", "law minimum",
                        "starts", "action", "enforcement", "holds", "last run", "outcome", "dry run", "last success",
                        "rows affected", "rows held", "next due", "overdue");
        var lines = new ArrayList<String>();
        lines.add(line(header));
        for (var c : report(locale).categories()) {
            var last = c.lastRun();
            lines.add(line(Arrays.asList(
                    c.code(),
                    c.module(),
                    c.name(),
                    c.clause(),
                    c.period(),
                    c.afterDisputeClosed(),
                    String.valueOf(c.lawMinimum()),
                    c.starts(),
                    c.action().code(),
                    c.enforcement().code(),
                    String.join(" ", c.holds()),
                    last == null ? null : last.finishedAt().toString(),
                    last == null ? null : last.outcome(),
                    last == null ? null : String.valueOf(last.dryRun()),
                    c.lastSuccessAt() == null ? null : c.lastSuccessAt().toString(),
                    String.valueOf(c.rowsAffected()),
                    String.valueOf(c.held()),
                    c.nextDueAt() == null ? null : c.nextDueAt().toString(),
                    String.valueOf(c.overdue()))));
        }
        return String.join("\r\n", lines) + "\r\n";
    }

    /** RFC 4180: every field quoted, quotes doubled. */
    private static String line(List<@Nullable String> fields) {
        return fields.stream()
                .map(f -> f == null ? "" : '"' + f.replace("\"", "\"\"") + '"')
                .collect(Collectors.joining(","));
    }

    private static Retention.RunView view(RunRecord r) {
        return new Retention.RunView(
                r.category(),
                r.dryRun(),
                r.trigger(),
                r.startedAt(),
                r.finishedAt(),
                r.outcome(),
                r.affected(),
                r.held(),
                r.remaining());
    }
}
