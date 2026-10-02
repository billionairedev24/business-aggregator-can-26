package ca.northline.privacy.application;

import ca.northline.shared.CodedEnum;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Period;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * S-107: the retention schedule — section 6 of the Privacy Policy as configuration ({@code
 * privacy/retention-schedule.yml}). One {@link Category} per kind of data: the policy's clause and words, the period and
 * what starts it, the legal basis, the action at expiry, the owning module and its legal holds. Periods are the
 * policy's, not environment settings.
 *
 * @param categories what the policy (and the audit log) schedules
 * @param operational short-lived technical data other jobs already delete, listed in the report
 */
public record RetentionCatalogue(List<Category> categories, List<Operational> operational) {

    public static final String RESOURCE = "privacy/retention-schedule.yml";

    public RetentionCatalogue {
        categories = List.copyOf(categories);
        operational = List.copyOf(operational);
        var codes = categories.stream().map(Category::code).distinct().count();
        if (codes != categories.size()) {
            throw new IllegalStateException("Two retention categories share a code");
        }
    }

    /** Reads the catalogue from the classpath. */
    public static RetentionCatalogue load() {
        try {
            var sources = new YamlPropertySourceLoader().load(RESOURCE, new ClassPathResource(RESOURCE));
            return new Binder(ConfigurationPropertySources.from(sources))
                    .bind("retention", Bindable.of(RetentionCatalogue.class))
                    .orElseThrow(() -> new IllegalStateException(RESOURCE + " has no retention catalogue"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<Category> category(String code) {
        return categories.stream().filter(c -> c.code().equals(code)).findFirst();
    }

    /** The categories a module's job carries out (enforcement {@code job} or {@code pipeline}). */
    public List<Category> runnable() {
        return categories.stream().filter(Category::runs).toList();
    }

    /** A text in English and French. */
    public record Text(String en, String fr) {

        public String in(Locale locale) {
            return locale.getLanguage().equals("fr") ? fr : en;
        }
    }

    /**
     * One category of data.
     *
     * @param clause the Privacy Policy's clause ({@code Messages and dispute evidence}); {@code null} when the policy
     *     doesn't name the data (flagged for counsel)
     * @param policy the clause's words, verbatim
     * @param period how long after {@link #starts} the data is kept; {@code null} when it has no end (reviews)
     * @param afterDisputeClosed a dispute about the row keeps it this long after the dispute is decided
     * @param lawMinimum also keep it {@code decision_retention_days} of the person's privacy law after a decision
     * @param holds the legal holds that stop the action ({@code PersonalDataContributor.Hold} codes)
     */
    public record Category(
            String code,
            String module,
            @Nullable String clause,
            @Nullable String policy,
            @Nullable Period period,
            @Nullable Period afterDisputeClosed,
            boolean lawMinimum,
            Text starts,
            Text name,
            Text basis,
            Action action,
            Enforcement enforcement,
            @Nullable List<String> holds,
            @Nullable Text note) {

        public boolean runs() {
            return enforcement == Enforcement.JOB || enforcement == Enforcement.PIPELINE;
        }

        public List<String> holdCodes() {
            return holds == null ? List.of() : List.copyOf(holds);
        }

        /** The period; only categories that run have one for sure. */
        public Period requiredPeriod() {
            return Objects.requireNonNull(period, () -> code + " has no period");
        }
    }

    /** What happens to a row when its period ends. */
    public enum Action implements CodedEnum {
        DELETE,
        /** The person's id and every personal field go; amounts and dates stay. */
        PSEUDONYMISE,
        /** Only totals stay. */
        AGGREGATE
    }

    /** Who carries the category out. */
    public enum Enforcement implements CodedEnum {
        /** The owning module's {@code RetentionContributor}, nightly. */
        JOB,
        /** The S-105 erasure pipeline (driven by the privacy module's job). */
        PIPELINE,
        /** Database backup and bucket settings (Terraform). */
        INFRASTRUCTURE,
        /** Kept as long as the policy says, with no end of its own. */
        NONE,
        /** Nothing in the product ends the period yet. */
        BLOCKED
    }

    /** A short-lived kind of technical data that another job deletes. */
    public record Operational(String code, String period, String where, String setting, Text name) {}
}
