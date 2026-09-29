package ca.northline;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Module boundaries (Spring Modulith). Docs land in build/spring-modulith-docs. */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(NorthlineApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        MODULES.verify();
    }

    @Test
    void writesDocs() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
