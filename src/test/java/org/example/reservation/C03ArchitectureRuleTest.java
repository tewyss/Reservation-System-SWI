package org.example.reservation;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Reservation;
import org.example.reservation.repository.CourtRepository;
import org.example.reservation.service.CourtAllocation;

import java.time.Instant;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * C03 L2 - the architecture rule derived from ADR-6 and the static view G2:
 *
 * <blockquote>Only Court Allocation may move a Reservation into CONFIRMED, and
 * only Court Allocation may take the court hold.</blockquote>
 *
 * BR-02 under concurrency (REQ-04 / REQ-15) is guaranteed by ONE element. That is
 * only true while no other code performs the transition or takes the lock on its
 * own - which is exactly how the AS-IS code was built (two methods, each
 * remembering the hold). This test turns "remember to go through the funnel" into
 * a build failure. It reads compiled production classes only.
 */
@AnalyzeClasses(packages = "org.example.reservation", importOptions = ImportOption.DoNotIncludeTests.class)
class C03ArchitectureRuleTest {

    private static final String COURT_ALLOCATION = CourtAllocation.class.getName();

    @ArchTest
    static final ArchRule only_court_allocation_moves_a_reservation_into_confirmed =
            noClasses().that().doNotHaveFullyQualifiedName(COURT_ALLOCATION)
                    .should().callMethod(Reservation.class, "confirm")
                    .orShould().callMethod(Reservation.class, "approve", AppUser.class, Instant.class)
                    .because("ADR-6: the transition into CONFIRMED is decided by Court Allocation "
                            + "under the court hold, whichever operation requests it");

    @ArchTest
    static final ArchRule only_court_allocation_takes_the_court_hold =
            noClasses().that().doNotHaveFullyQualifiedName(COURT_ALLOCATION)
                    .should().callMethod(CourtRepository.class, "findByIdForUpdate", Long.class)
                    .because("ADR-6: the per-court exclusive hold has one owner");

    /**
     * Guards the two rules above against passing vacuously: if a method were
     * renamed, "nobody else calls it" would be trivially true.
     */
    @ArchTest
    static final ArchRule court_allocation_is_the_path_that_exists =
            classes().that().haveFullyQualifiedName(COURT_ALLOCATION)
                    .should().callMethod(Reservation.class, "confirm")
                    .andShould().callMethod(Reservation.class, "approve", AppUser.class, Instant.class)
                    .andShould().callMethod(CourtRepository.class, "findByIdForUpdate", Long.class);
}
