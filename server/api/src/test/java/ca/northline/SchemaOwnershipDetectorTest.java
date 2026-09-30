package ca.northline;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.catalogue.fixture.CrossSchemaSqlFixture;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/** The S-37 rule's detector against a fixture: text blocks, concatenation, own schema, non-SQL strings. */
class SchemaOwnershipDetectorTest {

    @Test
    void findsOtherModulesSchemasInSqlStrings_only() {
        var fixture = new ClassFileImporter().importClass(CrossSchemaSqlFixture.class);
        assertThat(SchemaOwnershipTests.foreignSchemas(fixture)).containsExactly("merchants", "payments");
    }

    @Test
    void theOwningModuleIsThePackageAfterCaNorthline() {
        assertThat(SchemaOwnershipTests.moduleOf("ca.northline")).isNull();
        assertThat(SchemaOwnershipTests.moduleOf("ca.northline.food.persistence"))
                .isEqualTo("food");
    }
}
