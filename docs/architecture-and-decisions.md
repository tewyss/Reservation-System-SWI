# Architecture and Decisions

## Overview
*Updated in C03 — full views (context, components, state ownership, runtime, sequence,
design classes) are in [`c03-architecture.md`](c03-architecture.md).*

A single Spring Boot application with six logical elements:

```
              Reservation API (ReservationController)   HTTP + actor header; decides nothing
                 |                              |
                 v                              v
   Reservation Lifecycle  ------------->  Approval Workflow
   (ReservationService)                   (ApprovalWorkflow, ApprovalPolicy,
   create, confirm, cancel, availability   ApprovalExpiry, ApprovalExpirySweeper)
                 |     \                  /     approve, reject, expire
                 |      v                v         |
                 |      Court Allocation (CourtAllocation)   ADR-6: the ONLY path into
                 |              |                  |         CONFIRMED; owns court hold + BR-02
                 v              v                  v
   Notification Integration    Persistence (Spring Data JPA, @Version)   ADR-7
   (NotificationDispatcher,     |
    after commit)               v
       |                 H2 (dev/test) / PostgreSQL (prod)
       v
   NotificationService  ->  external Notification Service
```

- **domain** — JPA entities; `ReservationState` holds the BR-02 blocking set **and** the
  statechart's legal edges, and `Reservation` refuses an illegal edge (C03 ADR-7).
- **service** — the Lifecycle, Approval Workflow, Court Allocation and Notification
  Integration elements; talks to the DB through repositories and to the outside world only
  through `NotificationService`.
- **web** — thin REST layer (CP1 walking skeleton).

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
- **ADR-1 — Business rules live in the service, not the entity.** *(Refined by ADR-6/ADR-7:
  allocation rules live in Court Allocation, approval rules in Approval Workflow; the entity
  now also guards the statechart's legal edges.)* `ReservationService`
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
- **ADR-6 (C03) — The transition into `CONFIRMED` has one owner: Court Allocation.** It
  takes the per-court exclusive hold, evaluates BR-07/BR-04/BR-02 and performs the edge, for
  OP-03 and OP-05 alike. Chosen over a PostgreSQL exclusion constraint because it keeps the
  specified `CONFLICT` outcome with the rule owner and keeps BR-02 testable on H2. Enforced
  by `C03ArchitectureRuleTest`. Full record: [`c03-architecture.md` §F](c03-architecture.md#f-architecture-decision-records).
- **ADR-7 (C03) — Every reservation transition is a compare-and-set; outcomes are announced
  after commit.** `@Version` on `Reservation` makes approve vs reject / cancel / sweep on the
  same request yield exactly one outcome (V-06.5, V-04.11). Notifications go out after commit
  (ADR-3 kept). `PENDING_APPROVAL → EXPIRED` has one implementation (`ApprovalExpiry`, own
  transaction per reservation).

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

**Status after C03:** handled by ADR-6. The court hold has a single owner, a control
run shows the double-booking returns without it (8 of 8 concurrent confirms), and the
remaining risk under the 10× pressure is lock *wait* on popular courts, not
double-booking. A DB exclusion constraint stays the recorded next step (ADR-6
"reconsider when").
