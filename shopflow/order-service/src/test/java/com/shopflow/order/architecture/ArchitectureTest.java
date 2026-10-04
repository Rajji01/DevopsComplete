package com.shopflow.order.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture rules that fail the build, so "we agreed controllers never touch repositories"
 * stays true after the tenth contributor. Cheap to write, saves a review comment every week.
 */
@AnalyzeClasses(packages = "com.shopflow.order", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule controllers_do_not_talk_to_repositories = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .because("the web layer goes through services; repositories are a persistence detail");

    @ArchTest
    static final ArchRule services_do_not_depend_on_the_web_layer = noClasses()
            .that().haveSimpleNameEndingWith("Service")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web.bind..", "jakarta.servlet..")
            .because("services must be callable from Kafka listeners and schedulers, not only from HTTP");

    @ArchTest
    static final ArchRule controllers_do_not_return_entities = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class)
            .because("controllers return DTOs; exposing JPA entities leaks the schema and causes lazy-loading surprises");

    @ArchTest
    static final ArchRule controllers_are_named_so = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule no_field_injection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule no_system_out = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule no_java_util_logging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
}
