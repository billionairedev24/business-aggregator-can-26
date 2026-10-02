package ca.northline.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.privacy.application.RetentionCatalogue;
import ca.northline.privacy.application.RetentionCatalogue.Category;
import ca.northline.privacy.application.RetentionCatalogue.Enforcement;
import ca.northline.shared.privacy.PersonalDataContributor.Hold;
import ca.northline.shared.privacy.RetentionContributor;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Period;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * S-107: the retention schedule is the Privacy Policy's section 6, word for word ({@code
 * web/packages/legal/pages/privacy.html}, shipped verbatim from design 10): every clause there has a category here, every
 * category quotes its clause exactly, and each period is the one the clause states. A change to either fails here
 * until both agree.
 */
class RetentionScheduleTest {

    static final Path POLICY = Path.of("../../web/packages/legal/pages/privacy.html");

    final RetentionCatalogue catalogue = RetentionCatalogue.load();

    /** The text of "6. How long we keep it": from its heading to the next one. */
    static String section6() throws IOException {
        var html = Files.readString(POLICY);
        var start = html.indexOf("id=\"retain\"");
        var end = html.indexOf("<h2", start + 1);
        assertThat(start).as("section 6 of the policy").isPositive();
        return html.substring(start, end).replace("<br>", "\n");
    }

    @Test
    void everyClauseOfTheRetentionSectionHasACategory() throws IOException {
        var clauses = new TreeSet<String>();
        var strong = Pattern.compile("<strong>([^<]+):</strong>").matcher(section6());
        while (strong.find()) {
            clauses.add(strong.group(1));
        }
        assertThat(clauses).isNotEmpty();
        var covered = new TreeSet<String>();
        catalogue.categories().stream()
                .map(Category::clause)
                .filter(c -> c != null)
                .forEach(covered::add);
        assertThat(covered).containsAll(clauses);
        assertThat(section6()).contains("Backups roll off within 35 days of deletion.");
    }

    @Test
    void everyCategoryQuotesThePolicyWordForWord() throws IOException {
        var text = section6();
        for (var c : catalogue.categories()) {
            if (c.clause() == null) {
                assertThat(c.policy())
                        .as(c.code() + " is not in the policy: no quote")
                        .isNull();
                assertThat(c.basis().en())
                        .as(c.code() + " says it is for counsel")
                        .contains("Privacy Policy");
                continue;
            }
            var quote =
                    "Backups".equals(c.clause()) ? c.policy() : "<strong>" + c.clause() + ":</strong> " + c.policy();
            assertThat(text).as(c.code()).contains(quote);
        }
    }

    @Test
    void everyPeriodIsTheOneThePolicyStates() {
        for (var c : catalogue.categories()) {
            if (c.period() == null) {
                assertThat(c.enforcement()).as(c.code()).isIn(Enforcement.NONE);
                continue;
            }
            var words = words(c.period());
            if (c.clause() == null) {
                continue; // not in the policy (flagged in its basis)
            }
            assertThat(c.policy()).as(c.code() + " period " + c.period()).contains(words);
            if (c.afterDisputeClosed() != null && !c.afterDisputeClosed().isZero()) {
                assertThat(c.policy()).as(c.code()).contains("plus " + words(c.afterDisputeClosed()));
            }
        }
    }

    /** {@code P2Y} → "2 years", {@code P12M} → "12 months", {@code P30D} → "30 days" (the policy's wording). */
    static String words(Period p) {
        if (p.getYears() > 0) {
            return p.getYears() + (p.getYears() == 1 ? " year" : " years");
        }
        if (p.getMonths() > 0) {
            return p.getMonths() + " months";
        }
        return p.getDays() + " days";
    }

    @Test
    void holdsAreTheErasurePipelinesAndEveryCategoryIsComplete() {
        var codes = Arrays.stream(Hold.values()).map(Hold::code).toList();
        for (var c : catalogue.categories()) {
            assertThat(codes).as(c.code()).containsAll(c.holdCodes());
            if (c.enforcement() != Enforcement.PIPELINE) {
                assertThat(c.code()).as("codes are <module>.<data>").startsWith(c.module() + ".");
            }
            assertThat(c.name().fr()).as(c.code()).isNotBlank();
            assertThat(c.basis().fr()).as(c.code()).isNotBlank();
            assertThat(c.starts().fr()).as(c.code()).isNotBlank();
            if (c.enforcement() == Enforcement.BLOCKED || c.enforcement() == Enforcement.INFRASTRUCTURE) {
                assertThat(c.note()).as(c.code() + " explains itself").isNotNull();
            }
        }
        assertThat(catalogue.operational())
                .allSatisfy(o -> assertThat(o.name().fr()).isNotBlank());
    }

    @Test
    void everyCategoryWithAJobHasItsModulesContributor() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ca.northline");
        var modules = new TreeSet<String>();
        for (JavaClass c : classes) {
            if (c.isAssignableTo(RetentionContributor.class) && !c.isInterface()) {
                modules.add(c.getPackageName().split("\\.")[2]);
                assertThat(c.getPackageName()).as(c.getName()).endsWith(".persistence");
            }
        }
        var expected = new TreeSet<String>();
        catalogue.runnable().forEach(c -> expected.add(c.module()));
        assertThat(modules).isEqualTo(expected);
    }

    @Test
    void noProvinceIsWrittenIntoTheSchedule() throws IOException {
        var yml = Files.readString(Path.of("src/main/resources/" + RetentionCatalogue.RESOURCE));
        assertThat(yml)
                .doesNotContainIgnoringCase("alberta")
                .doesNotContainIgnoringCase("calgary")
                .doesNotContainIgnoringCase("edmonton")
                .doesNotContain("America/");
    }
}
