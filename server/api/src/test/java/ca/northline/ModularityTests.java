package ca.northline;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {
    ApplicationModules modules = ApplicationModules.of(NorthlineApplication.class);
    @Test void verifiesModuleBoundaries() { modules.verify(); }
    @Test void writesDocs() { new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml(); }
}
