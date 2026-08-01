package com.bbororo.rtb.dsp.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class DspBoundaryTest {

    @Test
    void dspDoesNotDependOnSspImplementation() {
        var dspClasses = new ClassFileImporter().importPackages("com.bbororo.rtb.dsp");

        noClasses()
                .that().resideInAPackage("com.bbororo.rtb.dsp..")
                .should().dependOnClassesThat().resideInAPackage("com.bbororo.rtb.ssp..")
                .check(dspClasses);
    }
}
