package com.movie.ticket_booking.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AGENTS.md section 5: every endpoint declares its own @PreAuthorize; nothing relies on a URL fallback. */
class ControllerAuthorizationRuleTest {
    private static final ArchRule EVERY_ENDPOINT_IS_AUTHORIZED = methods().that()
            .areDeclaredInClassesThat().resideInAPackage("..controller..")
            .and().areAnnotatedWith(GetMapping.class)
            .or().areAnnotatedWith(PostMapping.class)
            .or().areAnnotatedWith(PutMapping.class)
            .or().areAnnotatedWith(DeleteMapping.class)
            .or().areAnnotatedWith(PatchMapping.class)
            .or().areAnnotatedWith(RequestMapping.class)
            .should().beAnnotatedWith(PreAuthorize.class);

    @Test
    void everyControllerEndpointHasPreAuthorize() {
        EVERY_ENDPOINT_IS_AUTHORIZED.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.movie.ticket_booking"));
    }

    @Test
    void guardRejectsAnEndpointWithoutPreAuthorize() {
        assertTrue(EVERY_ENDPOINT_IS_AUTHORIZED.evaluate(new ClassFileImporter()
                .importClasses(com.movie.ticket_booking.architecture.fake.controller.UnprotectedEndpoint.class)).hasViolation());
    }
}
