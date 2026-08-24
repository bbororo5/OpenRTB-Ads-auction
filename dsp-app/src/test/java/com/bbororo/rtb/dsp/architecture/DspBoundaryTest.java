package com.bbororo.rtb.dsp.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class DspBoundaryTest {

    private static final JavaClasses DSP_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.bbororo.rtb.dsp");

    @Test
    void dspDoesNotDependOnSspImplementation() {
        noClasses()
                .that().resideInAPackage("com.bbororo.rtb.dsp..")
                .should().dependOnClassesThat().resideInAPackage("com.bbororo.rtb.ssp..")
                .check(DSP_CLASSES);
    }

    @Test
    void topLevelComponentsDoNotFormDependencyCycles() {
        slices()
                .matching("com.bbororo.rtb.dsp.(*)..")
                .should().beFreeOfCycles()
                .check(DSP_CLASSES);
    }

    @Test
    void componentDependenciesFollowTheAuthorityDirection() {
        assertDependencies("contract", "contract");
        assertDependencies("openrtb", "contract", "openrtb");
        assertDependencies("spending", "contract", "spending");
        assertDependencies("campaignruntime", "campaignruntime", "contract", "openrtb");
        assertDependencies("bidding", "bidding", "contract", "openrtb", "proof");
        assertDependencies("proof", "contract", "openrtb", "proof", "spending");
        assertDependencies("outcome", "contract", "openrtb", "outcome", "proof", "spending");
        assertDependencies("lease", "contract", "lease", "outcome", "spending");
        assertDependencies("responsibility", "contract", "responsibility");
    }

    @Test
    void retiredPackageNamesCannotReturn() {
        noClasses()
                .should().resideInAnyPackage(
                        "com.bbororo.rtb.dsp.auction..",
                        "com.bbororo.rtb.dsp.budget..",
                        "com.bbororo.rtb.dsp.campaign..",
                        "com.bbororo.rtb.dsp.notification..",
                        "com.bbororo.rtb.dsp.allocation.."
                )
                .check(DSP_CLASSES);
    }

    private static void assertDependencies(String component, String... dspDependencies) {
        String[] allowed = new String[dspDependencies.length + 2];
        allowed[0] = "java..";
        allowed[1] = "javax..";
        for (int i = 0; i < dspDependencies.length; i++) {
            allowed[i + 2] = "com.bbororo.rtb.dsp." + dspDependencies[i] + "..";
        }
        classes()
                .that().resideInAPackage("com.bbororo.rtb.dsp." + component + "..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(allowed)
                .check(DSP_CLASSES);
    }
}
