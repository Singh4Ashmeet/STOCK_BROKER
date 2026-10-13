package com.stockbroker;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

/**
 * Architecture tests enforcing modular monolith structure.
 *
 * <p>Rules:
 * <ul>
 *   <li>No cross-module repository access</li>
 *   <li>Controllers only in api packages</li>
 *   <li>Services only in service packages</li>
 *   <li>Repositories only in repo packages</li>
 *   <li>Domain entities only in domain packages</li>
 *   <li>Common code accessible by all modules</li>
 * </ul>
 */
class ArchitectureTest {

    private static final JavaClasses classes = new ClassFileImporter()
            .importPackages("com.stockbroker");

    @Test
    void modulesShouldNotAccessOtherModulesRepositories() {
        // Each module's repo should only be accessed by its own service
        modules().forEach(module -> {
            ArchRule rule = noClasses()
                    .that().resideInAPackage(".." + module + "..")
                    .should().accessClassesThat()
                    .resideInAnyPackage(otherModulesRepos(module))
                    .because("Module " + module + " should not access other modules' repositories");

            rule.check(classes);
        });
    }

    @Test
    void controllersShouldOnlyBeInApiPackages() {
        ArchRule rule = classes()
                .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should().resideInAPackage("..api..")
                .because("Controllers must be in api packages");

        rule.check(classes);
    }

    @Test
    void servicesShouldOnlyBeInServicePackages() {
        ArchRule rule = classes()
                .that().areAnnotatedWith("org.springframework.stereotype.Service")
                .should().resideInAPackage("..service..")
                .because("Services must be in service packages");

        rule.check(classes);
    }

    @Test
    void repositoriesShouldOnlyBeInRepoPackages() {
        ArchRule rule = classes()
                .that().areAnnotatedWith("org.springframework.stereotype.Repository")
                .should().resideInAPackage("..repo..")
                .because("Repositories must be in repo packages");

        rule.check(classes);
    }

    @Test
    void domainEntitiesShouldOnlyBeInDomainPackages() {
        ArchRule rule = classes()
                .that().areAnnotatedWith("jakarta.persistence.Entity")
                .should().resideInAPackage("..domain..")
                .because("Entities must be in domain packages");

        rule.check(classes);
    }

    @Test
    void commonCodeShouldNotDependOnModules() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.stockbroker.common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.stockbroker.auth..",
                        "com.stockbroker.market..",
                        "com.stockbroker.portfolio..",
                        "com.stockbroker.trading..",
                        "com.stockbroker.alerts..",
                        "com.stockbroker.research..",
                        "com.stockbroker.admin.."
                )
                .because("Common code must not depend on module-specific code");

        rule.check(classes);
    }

    @Test
    void noControllerShouldAccessRepositoryDirectly() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().accessClassesThat()
                .resideInAPackage("..repo..")
                .because("Controllers must not access repositories directly; use services");

        rule.check(classes);
    }

    @Test
    void servicesShouldNotAccessOtherModuleServices() {
        modules().forEach(module -> {
            ArchRule rule = noClasses()
                    .that().resideInAPackage(".." + module + ".service..")
                    .should().accessClassesThat()
                    .resideInAnyPackage(otherModulesServices(module))
                    .because("Services in " + module + " should not access other modules' services directly");

            rule.check(classes);
        });
    }

    @Test
    void dtosShouldBeInApiPackage() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Dto")
                .or().haveSimpleNameEndingWith("Request")
                .or().haveSimpleNameEndingWith("Response")
                .should().resideInAPackage("..api..")
                .because("DTOs must be in api packages");

        rule.check(classes);
    }

    @Test
    void recordsShouldBeUsedForDtos() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Dto")
                .or().haveSimpleNameEndingWith("Request")
                .or().haveSimpleNameEndingWith("Response")
                .should().beRecords()
                .because("DTOs should be immutable records");

        rule.check(classes);
    }

    private static List<String> modules() {
        return List.of("auth", "market", "portfolio", "trading", "alerts", "research", "admin");
    }

    private static String[] otherModulesRepos(String module) {
        return modules().stream()
                .filter(m -> !m.equals(module))
                .map(m -> "com.stockbroker." + m + ".repo..")
                .toArray(String[]::new);
    }

    private static String[] otherModulesServices(String module) {
        return modules().stream()
                .filter(m -> !m.equals(module))
                .map(m -> "com.stockbroker." + m + ".service..")
                .toArray(String[]::new);
    }
}