# Architecture and Decisions

## Overview
A layered Spring Boot application:

```
web (ReservationController, REST)
      |
service (ReservationService  ->  NotificationService boundary)
      |
repository (Spring Data JPA)
      |
domain (Court, AppUser, Reservation, ReservationState)
      |
database (H2 in dev/test, PostgreSQL in prod)
```

- **domain** — plain JPA entities and the state enum; holds the small invariants
  (e.g. `Reservation.overlaps`).
- **service** — `ReservationService` owns the four operations and both business
  rules; talks to the DB through repositories and to the outside world through
  the `NotificationService` boundary.
- **web** — thin REST layer; starts the CP1 walking skeleton.

## Technology stack and rationale
| Choice | Rationale |
| --- | --- |
| **Java 21** | Matches the supported stack and the repository's existing Maven/JDK 21 setup. |
| **Spring Boot 3.5** | Batteries-included web + data + test; the REST walking skeleton (`POST /reservations`) is natural and cheap. |
| **Spring Data JPA / Hibernate** | Persistence is core to a reservation system; JPA gives us mapping + a repository layer with almost no boilerplate, and a clean seam for the overlap query. |
| **H2 (dev/test) + PostgreSQL (prod)** | H2 in-memory keeps the build and the persistence spike runnable anywhere with **no external DB or Docker**; PostgreSQL is the real target (`postgres` profile). H2 runs in `MODE=PostgreSQL` to reduce dialect drift. |
| **JUnit 5 + Spring Boot Test / AssertJ** | Standard, already on the classpath via `spring-boot-starter-test`; supports both the `@DataJpaTest` persistence spike and full-context service tests. |
| **Maven** | Already the project's build tool (IntelliJ-bundled Maven 3.9.9 verified against JDK 21). |

## Key decisions
- **ADR-1 — Business rules live in the service, not the entity.** `ReservationService`
  centralises the overlap rule and the opening-hours rule so they are enforced in
  one place at confirm time and are easy to unit-test. Entities keep only local
  invariants.
- **ADR-2 — Notification is a boundary interface.** `NotificationService` is an
  interface with a `LoggingNotificationService` default. This isolates the one
  external dependency and lets us stub it in tests and swap the provider later.
- **ADR-3 — Notification failure must not undo a confirmation.** A confirmed
  reservation is valid business state even if the message never sends; the
  `NotificationException` is caught and logged.
- **ADR-4 — Only CONFIRMED reservations block a court.** DRAFT and CANCELLED
  reservations are ignored by the overlap query, so drafting never blocks others.
- **ADR-5 — Half-open time intervals `[start, end)`.** Back-to-back bookings
  (…–11:00 and 11:00–…) do not count as an overlap.

## Selected future pressure
**Category: Q (Quality / Scale).**

**Concrete pressure:** 10× more concurrent members, so many **confirm** requests
for the *same court and overlapping slot* can arrive at nearly the same instant.

**Why it is relevant to our reservation system:** our common rule ("no two
confirmed reservations of the same court overlap") is currently enforced with a
*check-then-act* sequence in `ReservationService.confirm` — read overlapping
reservations, then write CONFIRMED. Under high concurrency two requests can both
pass the check before either writes, producing a real **double-booking** — the
exact failure the system exists to prevent. Handling this later will need a
DB-level guarantee (unique/exclusion constraint or pessimistic/serializable
locking), not just application code. We are **not** implementing this in C01; we
are naming it so the design can evolve toward it. It also builds directly on the
C01 persistence spike, which confirmed the real DB round-trip we would harden.
