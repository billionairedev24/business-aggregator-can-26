package ca.northline;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.StringEntry;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * S-37: each module's SQL touches only its own schema. Other modules' data is read through their {@code api} package
 * (for merchants: {@code MerchantDirectory}, {@code MerchantVerifications}). ArchUnit sees no string literals, so the
 * condition reads each class file's constant pool with the JDK class-file API. Text blocks and the literal parts of
 * concatenated SQL both end up there as string constants.
 */
@AnalyzeClasses(packages = "ca.northline", importOptions = ImportOption.DoNotIncludeTests.class)
class SchemaOwnershipTests {

    /** Module packages that own a schema of the same name ({@code events} is the platform outbox, owned by none). */
    static final Set<String> MODULE_SCHEMAS = Set.of(
            "identity",
            "region",
            "merchants",
            "catalogue",
            "food",
            "availability",
            "booking",
            "orders",
            "fulfilment",
            "payments",
            "trust",
            "messaging",
            "developer",
            "account");

    /**
     * Known cross-schema reads kept on purpose, by class → schemas. Keep this list short and give each entry a reason
     * and a follow-up. A new entry needs a DECISIONS.md line.
     */
    static final Map<String, Set<String>> ALLOWED = Map.of(
            // The kitchen live board joins orders, order lines, group orders and courier stops in one query per
            // refresh. orders already depends on food, so moving it behind orders.api needs its own design (a
            // read model or an SPI). Follow-up to S-37.
            "ca.northline.food.persistence.KitchenTicketJdbc", Set.of("orders", "fulfilment"),
            "ca.northline.food.persistence.KitchenOrderLinesJdbc", Set.of("orders"),
            "ca.northline.food.persistence.KitchenNavBadges", Set.of("orders"));

    static final Pattern SQL = Pattern.compile("(?is)\\b(select|insert\\s+into|update|delete\\s+from|join|from)\\b");
    static final Pattern SCHEMA_REF = Pattern.compile("\\b(" + String.join("|", MODULE_SCHEMAS) + ")\\.[a-z_]+\\b");

    @ArchTest
    static final ArchRule sqlStaysInItsOwnSchema = noClasses()
            .that(new DescribedPredicate<JavaClass>("are in the api app (not the seeding tools)") {
                @Override
                public boolean test(JavaClass c) {
                    // ca.northline.tools = build-time seeders run by Gradle tasks (seedCategories), not app code
                    return c.getPackageName().startsWith("ca.northline.")
                            && !c.getPackageName().startsWith("ca.northline.tools");
                }
            })
            .should(new ArchCondition<JavaClass>("run SQL against another module's schema") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    var foreign = foreignSchemas(javaClass);
                    if (!foreign.isEmpty()) {
                        events.add(SimpleConditionEvent.satisfied(
                                javaClass,
                                "%s has SQL on schema(s) %s; use that module's api package (S-37)"
                                        .formatted(javaClass.getName(), foreign)));
                    }
                }
            })
            .because("a module's tables are private to it; other modules read them through its api (S-37)");

    /** The schemas of other modules named in the class's SQL strings, minus the allowed ones. */
    static Set<String> foreignSchemas(JavaClass javaClass) {
        var owner = moduleOf(javaClass.getPackageName());
        var allowed = ALLOWED.getOrDefault(javaClass.getName(), Set.of());
        return sqlStrings(javaClass).stream()
                .flatMap(sql -> SCHEMA_REF.matcher(sql).results().map(m -> m.group(1)))
                .filter(schema -> !schema.equals(owner) && !allowed.contains(schema))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    static @Nullable String moduleOf(String packageName) {
        var parts = packageName.split("\\.");
        return parts.length > 2 ? parts[2] : null;
    }

    static Set<String> sqlStrings(JavaClass javaClass) {
        var source = javaClass.getSource().orElse(null);
        if (source == null) {
            return Set.of();
        }
        byte[] bytes;
        try (var in = source.getUri().toURL().openStream()) {
            bytes = in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        var strings = new TreeSet<String>();
        for (var entry : ClassFile.of().parse(bytes).constantPool()) {
            if (entry instanceof StringEntry s && SQL.matcher(s.stringValue()).find()) {
                strings.add(s.stringValue());
            }
        }
        return strings;
    }
}
