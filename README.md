# SWI Reservation System — Sports Facility Court Booking

Engineering project for **SWI**. The system reserves **sports courts**: members
book a court for a time slot, and the system guarantees no two confirmed bookings
of the same court overlap.

## Team

- **Team name:** Holy Trio
- **Members (3):**
  - Pavel Valošek
  - Rostislav Nevoral
  - Pavel Mynář
- **Repository:** https://github.com/tewyss/Reservation-System-SWI

## What the system does
| Element | This project |
| --- | --- |
| **Resource** | `Court` (tennis / squash / badminton / basketball / football), with opening hours |
| **Reservation** | a court booked by a user for `[startTime, endTime)` with a state |
| **User** | `AppUser` who creates the reservation |
| **States** | `DRAFT` → `CONFIRMED` → `CANCELLED` |
| **Operations** | create, confirm/approve, cancel, check availability |
| **Common rule** | two `CONFIRMED` reservations of the same court must not overlap |
| **Domain rule** | a reservation must lie within the court's opening hours |
| **Boundary** | `NotificationService` (notifies the member on confirmation) |

Full details are in [`docs/intent-and-change.md`](docs/intent-and-change.md)
(Project Frame) and [`docs/architecture-and-decisions.md`](docs/architecture-and-decisions.md).

## Tech stack
Java 21 · Spring Boot 3.5 · Spring Data JPA · **H2** (dev/test) / **PostgreSQL**
(prod) · JUnit 5 · Maven. Rationale is in
[`docs/architecture-and-decisions.md`](docs/architecture-and-decisions.md#technology-stack-and-rationale).

## Build & run

### Prerequisites
- JDK 21 (`java -version` → 21.x)
- Maven 3.9+ (or the Maven bundled with IntelliJ IDEA)

### Run the tests (includes the persistence spike)
```bash
mvn test
```
Expected: `Tests run: 6, Failures: 0, Errors: 0` → `BUILD SUCCESS`.

### Run the application (H2, default profile)
```bash
mvn spring-boot:run
```
The app starts on `http://localhost:8080`. H2 console: `http://localhost:8080/h2-console`
(JDBC URL `jdbc:h2:mem:reservations`, user `sa`, empty password).

### Run against PostgreSQL (prod profile)
```bash
docker run --name swi-pg -e POSTGRES_DB=reservations -e POSTGRES_USER=swi \
  -e POSTGRES_PASSWORD=swi -p 5432:5432 -d postgres:16
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

## CP1 walking skeleton
The end-to-end path that must be **truly runnable after C03 / before C04**:

```
POST /reservations
  → validate (start<end, court & user exist)
  → persist (Reservation saved as DRAFT via Spring Data JPA)
  → return reservation ID (HTTP 201 + JSON body with id)
  → automated check (a test posts a reservation and asserts a persisted id is returned)
```

Status in C01: the route and layers exist
(`ReservationController.create` → `ReservationService.create` → `ReservationRepository`);
the dedicated end-to-end automated check is completed by CP1.

## Repository layout
```
README.md
docs/
  intent-and-change.md            # Project Frame
  architecture-and-decisions.md   # stack rationale, ADRs, future pressure
  evidence-and-evolution.md       # C01 spike: question, method, result, decision
  c01-engineering-spike.md        # change + review-cycle record
  evidence/
    spike-A-persistence-run.log   # committed spike run output
src/
  main/java/org/example/reservation/   # domain, repository, service, web
  main/resources/                      # application(.postgres).properties
  test/java/org/example/reservation/   # persistence spike + business-rule tests
```
