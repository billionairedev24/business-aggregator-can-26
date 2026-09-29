package ca.northline;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.web.bind.annotation.RestController;

/**
 * Layering and interface rules from ARCHITECTURE.md § Code standards, applied to modules that use the layered
 * layout ({@code <module>.web / application / domain / persistence}, see merchants).
 */
@AnalyzeClasses(packages = "ca.northline", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses()
            .that()
            .resideInAPackage("ca.northline..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "org.apache.kafka..",
                    "tools.jackson..",
                    "..web..",
                    "..persistence..",
                    "..application..")
            .because("domain code imports no Spring/JPA/Kafka types and knows no adapters");

    @ArchTest
    static final ArchRule applicationDoesNotKnowAdapters = noClasses()
            .that()
            .resideInAPackage("ca.northline..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..web..", "..persistence..")
            .because("application services talk to outbound ports, implemented by adapters");

    @ArchTest
    static final ArchRule controllersNeverTouchRepositories = noClasses()
            .that()
            .resideInAPackage("ca.northline..web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..persistence..")
            .orShould()
            .dependOnClassesThat()
            .areAssignableTo(org.springframework.data.repository.Repository.class)
            .because("web adapter → application service → port");

    @ArchTest
    static final ArchRule controllersLiveInWeb = classes()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .resideInAPackage("..web..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rowsLiveInPersistence = classes()
            .that()
            .areAnnotatedWith(Table.class)
            .and()
            .resideOutsideOfPackage("ca.northline.booking") // legacy scaffold, owned by the booking workstream
            .should()
            .resideInAPackage("..persistence..");

    @ArchTest
    static final ArchRule constructorInjectionOnly = noFields()
            .should()
            .beAnnotatedWith(Autowired.class)
            .because("constructor injection only (@RequiredArgsConstructor)");
}
