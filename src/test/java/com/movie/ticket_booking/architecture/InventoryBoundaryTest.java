package com.movie.ticket_booking.architecture;

import com.movie.ticket_booking.repository.ShowtimeSeatRepository;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryBoundaryTest {
    private static final ArchRule INVENTORY_BOUNDARY = noClasses().that()
            .resideOutsideOfPackage("..service.inventory..")
            .should().dependOnClassesThat().haveSimpleName("ShowtimeSeatRepository");

    @Test
    void onlyInventoryMayDependOnSeatInventoryRepository() {
        INVENTORY_BOUNDARY.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.movie.ticket_booking"));
    }

    @Test
    void guardRejectsAnIllegalRepositoryDependency() {
        assertTrue(INVENTORY_BOUNDARY.evaluate(new ClassFileImporter()
                .importClasses(ForbiddenInventoryAccess.class)).hasViolation());
    }

    static class ForbiddenInventoryAccess {
        private ShowtimeSeatRepository repository;
    }
}
