package org.example.reservation.demo;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;
import org.example.reservation.repository.AppUserRepository;
import org.example.reservation.repository.CourtRepository;
import org.example.reservation.service.ApprovalWorkflow;
import org.example.reservation.service.ReservationService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Fills the database with sample courts, users and reservations in every
 * lifecycle state, and leaves the application RUNNING - so the data can be
 * inspected in the H2 console or used to try the REST API by hand.
 *
 * <pre>mvn spring-boot:run -Dspring-boot.run.profiles=seed</pre>
 *
 * Unlike {@link SpecificationDemoRunner} it checks nothing and does not exit.
 * Every reservation is moved through the services, never by setting its state,
 * so the seeded data obeys the same rules as data entered over HTTP.
 */
@Component
@Profile("seed")
public class DataSeeder implements ApplicationRunner {

    private final ReservationService service;
    private final ApprovalWorkflow approvals;
    private final CourtRepository courts;
    private final AppUserRepository users;
    private final Clock clock;

    public DataSeeder(ReservationService service, ApprovalWorkflow approvals,
                      CourtRepository courts, AppUserRepository users, Clock clock) {
        this.service = service;
        this.approvals = approvals;
        this.courts = courts;
        this.users = users;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);

        // --- Courts -------------------------------------------------------
        Long court1 = courts.save(new Court("Kurt 1", Court.CourtType.TENNIS, "Hala A",
                LocalTime.of(8, 0), LocalTime.of(22, 0), true, false)).getId();
        Long court2 = courts.save(new Court("Kurt 2", Court.CourtType.SQUASH, "Hala B",
                LocalTime.of(7, 0), LocalTime.of(23, 0), true, false)).getId();
        Long centre = courts.save(new Court("Centralni kurt", Court.CourtType.TENNIS, "Hala A",
                LocalTime.of(8, 0), LocalTime.of(22, 0), true, true)).getId();
        Long closed = courts.save(new Court("Kurt 9 (udrzba)", Court.CourtType.BADMINTON, "Hala C",
                LocalTime.of(8, 0), LocalTime.of(22, 0), false, false)).getId();

        // --- Users --------------------------------------------------------
        Long alice = users.save(new AppUser("Alice", "alice@example.com", AppUser.Role.MEMBER)).getId();
        Long bob = users.save(new AppUser("Bob", "bob@example.com", AppUser.Role.MEMBER)).getId();
        Long dana = users.save(new AppUser("Dana (staff)", "dana@example.com", AppUser.Role.STAFF)).getId();
        Long eva = users.save(new AppUser("Eva (staff)", "eva@example.com", AppUser.Role.STAFF)).getId();

        // --- Reservations, one per interesting state ----------------------
        Long confirmed = service.confirm(alice,
                service.create(alice, court1, at(tomorrow, 10, 0), at(tomorrow, 11, 0)).getId()).getId();
        Long touching = service.confirm(bob,
                service.create(bob, court1, at(tomorrow, 11, 0), at(tomorrow, 12, 0)).getId()).getId();
        Long draft = service.create(bob, court1, at(tomorrow, 10, 30), at(tomorrow, 11, 30)).getId();

        Long cancelled = service.create(alice, court2, at(tomorrow, 18, 0), at(tomorrow, 19, 0)).getId();
        service.confirm(alice, cancelled);
        service.cancel(alice, cancelled);

        Long pendingAlice = service.confirm(alice,
                service.create(alice, centre, at(tomorrow, 14, 0), at(tomorrow, 15, 0)).getId()).getId();
        Long pendingBob = service.confirm(bob,
                service.create(bob, centre, at(tomorrow, 14, 0), at(tomorrow, 15, 0)).getId()).getId();

        Long approved = service.confirm(bob,
                service.create(bob, centre, at(tomorrow, 16, 0), at(tomorrow, 17, 0)).getId()).getId();
        approvals.approve(dana, approved);

        Long rejected = service.confirm(alice,
                service.create(alice, centre, at(tomorrow, 17, 0), at(tomorrow, 18, 0)).getId()).getId();
        approvals.reject(eva, rejected, "Kurt je rezervovan pro turnaj");

        // A request for a slot that already started: BR-10 clamps its deadline to
        // the start, so it is overdue at once and the sweep expires it.
        Reservation overdue = service.confirm(alice,
                service.create(alice, centre, at(yesterday, 14, 0), at(yesterday, 15, 0)).getId());
        approvals.expirePendingApprovals();

        // --- Summary ------------------------------------------------------
        String line = "=".repeat(78);
        System.out.println();
        System.out.println(line);
        System.out.println("SEED DATA LOADED - the application keeps running (Ctrl+C to stop)");
        System.out.println(line);
        System.out.printf("  courts: Kurt 1=%d, Kurt 2=%d, Centralni (approval)=%d, Kurt 9 (inactive)=%d%n",
                court1, court2, centre, closed);
        System.out.printf("  users:  Alice=%d, Bob=%d (MEMBER); Dana=%d, Eva=%d (STAFF)%n",
                alice, bob, dana, eva);
        System.out.printf("  reservations (day %s):%n", tomorrow);
        System.out.printf("    %d CONFIRMED         Alice, Kurt 1, 10:00-11:00%n", confirmed);
        System.out.printf("    %d CONFIRMED         Bob,   Kurt 1, 11:00-12:00 (touches the one above)%n", touching);
        System.out.printf("    %d DRAFT             Bob,   Kurt 1, 10:30-11:30 (confirming it -> CONFLICT)%n", draft);
        System.out.printf("    %d CANCELLED         Alice, Kurt 2, 18:00-19:00%n", cancelled);
        System.out.printf("    %d PENDING_APPROVAL  Alice, Centralni, 14:00-15:00%n", pendingAlice);
        System.out.printf("    %d PENDING_APPROVAL  Bob,   Centralni, 14:00-15:00 (competes with Alice)%n", pendingBob);
        System.out.printf("    %d CONFIRMED         Bob,   Centralni, 16:00-17:00 (approved by Dana)%n", approved);
        System.out.printf("    %d REJECTED          Alice, Centralni, 17:00-18:00 (rejected by Eva)%n", rejected);
        System.out.printf("    %d EXPIRED           Alice, Centralni, %s 14:00-15:00 (deadline passed)%n",
                overdue.getId(), yesterday);
        System.out.println("  H2 console: http://localhost:8080/h2-console"
                + "  (JDBC URL jdbc:h2:mem:reservations, user sa, empty password)");
        System.out.println(line);
    }

    private static LocalDateTime at(LocalDate day, int hour, int minute) {
        return LocalDateTime.of(day, LocalTime.of(hour, minute));
    }
}
