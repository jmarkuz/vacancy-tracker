package com.vacancytracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Package boundaries from docs/architecture.md section 3 (T-069). Production classes only. Each module may use
 * the modules listed for it and no others, nothing may use web, and the modules form no cycle.
 */
@AnalyzeClasses(packages = "com.vacancytracker", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.vacancytracker.";

    private static ArchRule onlyDependsOn(String module, String... allowed) {
        String[] own = new String[allowed.length + 1];
        own[0] = BASE + module + "..";
        for (int i = 0; i < allowed.length; i++) {
            own[i + 1] = BASE + allowed[i] + "..";
        }
        String[] others = java.util.stream.Stream.of("vacancy", "collection", "matching", "export", "notification", "web", "common")
                .filter(m -> !m.equals(module) && !java.util.Arrays.asList(allowed).contains(m))
                .map(m -> BASE + m + "..")
                .toArray(String[]::new);
        return noClasses().that().resideInAPackage(BASE + module + "..")
                .should().dependOnClassesThat().resideInAnyPackage(others)
                .as(module + " depends on nothing but " + String.join(", ", allowed) + " among the application modules")
                .allowEmptyShould(true);
    }

    @ArchTest
    static final ArchRule nothingDependsOnWeb = noClasses().that().resideOutsideOfPackage(BASE + "web..")
            .should().dependOnClassesThat().resideInAPackage(BASE + "web..")
            .as("no package depends on web");

    @ArchTest
    static final ArchRule commonDependsOnNoModule = onlyDependsOn("common");

    @ArchTest
    static final ArchRule vacancyDependsOnCommonOnly = onlyDependsOn("vacancy", "common");

    @ArchTest
    static final ArchRule matchingDependsOnVacancy = onlyDependsOn("matching", "vacancy", "common");

    @ArchTest
    static final ArchRule collectionDependsOnVacancyAndMatching = onlyDependsOn("collection", "vacancy", "matching", "common");

    @ArchTest
    static final ArchRule exportDependsOnVacancyAndMatching = onlyDependsOn("export", "vacancy", "matching", "common");

    @ArchTest
    static final ArchRule notificationDependsOnCollectionToo = onlyDependsOn("notification", "vacancy", "matching", "collection", "common");

    @ArchTest
    static final ArchRule modulesHaveNoCycles = slices().matching(BASE + "(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule webIsTheOnlyPlaceWithControllers = classes().that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideInAPackage(BASE + "web..")
            .as("REST controllers live in web");
}
