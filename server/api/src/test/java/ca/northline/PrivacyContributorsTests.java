package ca.northline;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.privacy.PersonalDataContributor;
import ca.northline.shared.privacy.RetentionContributor;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * S-105: every module that owns a schema takes part in privacy requests through its own {@link
 * PersonalDataContributor} (so a new module with personal data can't be forgotten by the erasure pipeline), each one
 * a package-private adapter over its own schema; the privacy module reaches other modules only through their APIs.
 */
@AnalyzeClasses(packages = "ca.northline", importOptions = ImportOption.DoNotIncludeTests.class)
class PrivacyContributorsTests {

    @Test
    void everyModuleWithASchemaHasAContributor() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ca.northline");
        var contributing = new TreeSet<String>();
        for (JavaClass c : classes) {
            if (c.isAssignableTo(PersonalDataContributor.class) && !c.isInterface()) {
                contributing.add(c.getPackageName().split("\\.")[2]);
            }
        }
        var expected = new TreeSet<>(SchemaOwnershipTests.MODULE_SCHEMAS);
        expected.remove("privacy"); // the requests themselves: the accountability record, ids only
        assertThat(contributing).isEqualTo(expected);
    }

    @ArchTest
    static final ArchRule contributorsArePackagePrivateAdaptersOfTheirModule = classes()
            .that()
            .implement(PersonalDataContributor.class)
            .should()
            .resideInAPackage("ca.northline.*.persistence")
            .andShould()
            .notHaveModifier(JavaModifier.PUBLIC)
            .because("each module erases only its own schema, behind its own adapter (S-105)");

    @ArchTest
    static final ArchRule retentionJobsArePackagePrivateAdaptersOfTheirModule = classes()
            .that()
            .implement(RetentionContributor.class)
            .should()
            .resideInAPackage("ca.northline.*.persistence")
            .andShould()
            .notHaveModifier(JavaModifier.PUBLIC)
            .because("each module purges only its own schema, behind its own adapter (S-107)");

    @ArchTest
    static final ArchRule privacyKnowsOtherModulesOnlyThroughTheirApis = classes()
            .that()
            .resideInAPackage("ca.northline.privacy..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "ca.northline.privacy..",
                    "ca.northline.*.api..",
                    "ca.northline.shared..",
                    "ca.northline.platform..",
                    "ca.northline.sms..",
                    "java..",
                    "jakarta..",
                    "org..",
                    "io.swagger..",
                    "io.micrometer..",
                    "lombok..",
                    "tools.jackson..",
                    "com.fasterxml..")
            .because("the privacy module reads no other module's tables and calls no module's internals (S-105)");
}
