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
| **States** | `DRAFT` → `PENDING_APPROVAL` → `CONFIRMED` → `CANCELLED`, plus terminal `REJECTED` / `EXPIRED` |
| **Operations** | create, check availability, confirm, cancel — plus approve, reject and expire (C02 approval change) |
| **Common rule** | two `CONFIRMED` reservations of the same court must not overlap |
| **Domain rule** | a reservation must lie within the court's opening hours |
| **Boundary** | `NotificationService` (confirmation, approval pending, decision outcome) |

Full details are in [`docs/intent-and-change.md`](docs/intent-and-change.md)
(Project Frame) and [`docs/architecture-and-decisions.md`](docs/architecture-and-decisions.md).

## Specification (C02)
The system's behaviour is specified before it is coded, and the code is verified
against that specification:

| Document | Contents |
| --- | --- |
| [`docs/c02-baseline-v0.1.md`](docs/c02-baseline-v0.1.md) | **Specification Baseline v0.1** — shared rules BR-01…BR-07, requirements REQ-01…REQ-08, full slices for the four core operations, the requirement acceptance gate, use-case view, lifecycle statechart, activity flows, consistency review. |
| [`docs/c02-change-v0.2-approval.md`](docs/c02-change-v0.2-approval.md) | **The approval-workflow change → Baseline v0.2** — impact analysis (affected *and* unaffected, with reasons), BR-08…BR-11, REQ-09…REQ-15, OP-05 Approve / OP-06 Reject / OP-07 Expire, updated views, architecture drivers. |
| [`docs/c02-evidence.md`](docs/c02-evidence.md) | **Evidence** — which examples were executed, the four mismatches found and where each defect actually was, and the architecture drivers carried into C03. |

## Architecture (C03)
| Document | Contents |
| --- | --- |
| [`docs/c03-architecture.md`](docs/c03-architecture.md) | AS-IS mapping (Part A), drivers, domain model, responsibilities, the decision question with two alternatives walked through one scenario, **ADR-6** (one owner of the transition into CONFIRMED) and **ADR-7** (atomic transitions, notify after commit), context / component / state-ownership / runtime / sequence / design-class views, cross-view check, AS-IS→TO-BE delta, verification. |
| [`docs/evidence-and-evolution.md`](docs/evidence-and-evolution.md#c03--architecture-evidence) | C03 evidence summary. |

The architecture rule *"only Court Allocation moves a reservation into CONFIRMED or takes
the court hold"* is an ArchUnit test (`C03ArchitectureRuleTest`) in the normal build.

Every test in the suite is named with the verification-example id (`V-03.9`,
`V-04.4`, …) it executes, so `mvn test` output doubles as the traceability matrix.

## Tech stack
Java 21 · Spring Boot 3.5 · Spring Data JPA · **H2** (dev/test) / **PostgreSQL**
(prod) · JUnit 5 · Maven. Rationale is in
[`docs/architecture-and-decisions.md`](docs/architecture-and-decisions.md#technology-stack-and-rationale).

## Build & run

### Prerequisites
- JDK 21 (`java -version` → 21.x)
- Maven 3.9+ (or the Maven bundled with IntelliJ IDEA)

### Run the tests (specification verification suites, C03 race + architecture tests, CP1 walking skeleton, C08/C09 cancellation HTTP tests, C10 failure logging, C01 spike)
```bash
mvn clean verify
```
Expected: `Tests run: 107, Failures: 0, Errors: 0` → `BUILD SUCCESS`, and
`target/SWIReservationSystem-1.0-SNAPSHOT.jar`. No environment variables, no `.env` and no
database are needed (tests use in-memory H2).

### CI and the one-command check (C09)
- **GitHub Actions** `.github/workflows/ci.yml` runs on every push / pull request:
  job `build-test` = `mvn -B clean verify` + SHA-256 of the JAR (uploaded with the surefire
  reports); job `postgres-integration` = the HTTP integration tests (CP1, C08, C09) against a
  PostgreSQL 16 service container.
- **Without CI** the same steps run locally:
  ```bash
  scripts/verify.sh              # clean verify + JAR hash, log in target/verify.log
  scripts/verify.sh --postgres   # + HTTP integration tests on the swi-pg container (see below)
  ```
  Set `MVN=<path to mvn>` if Maven is not on `PATH`. Exit code 0 = all executed checks passed.

### Watch the specification run (scripted demonstration)
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```
Boots the application, walks a member, a second member and an approver through all
seven operations, and prints the observed outcome of each check. Expected:
`RESULT: 28 checks, 0 failed`. A committed transcript is in
[`docs/evidence/c03-to-be-demo-run.log`](docs/evidence/c03-to-be-demo-run.log).

### Run with sample data (stays running)
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=seed
```
Loads four courts (one approval-gated, one inactive), two members, two staff and a
reservation in every lifecycle state, prints their ids and keeps running, so the data
can be inspected in the H2 console or used with the REST API.

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

## Configuration (non-secret)
| Property | Default | Meaning |
| --- | --- | --- |
| `reservation.approval.window` | `PT24H` | BR-10 approval window (A-7) |
| `reservation.approval.sweep.enabled` / `sweep-interval` | `true` / `PT5M` | OP-07 background sweep |
| `reservation.cancellation.notice-period` | `PT2H` | BR-03.6 (C08 CR-01): a MEMBER must cancel a CONFIRMED booking at least this long before start; `PT0S` = no notice period |
| `spring.datasource.hikari.connection-timeout` (postgres profile) | `5000` ms | fail fast when the DB is down (C10) |

## REST surface
The actor is carried in the `X-Actor-Id` header (assumption A-1: asserted, not yet
authenticated — architecture driver AD-3 for C03).

```
POST /reservations                 -> OP-01 Create             201 {id, state}
GET  /reservations/availability    -> OP-02 Check Availability  {available, reason,
                                                                 approvalRequired,
                                                                 pendingApprovalCount}
POST /reservations/{id}/confirm     -> OP-03 Confirm / submit
POST /reservations/{id}/cancel      -> OP-04 Cancel (member + CONFIRMED: >= notice period before start)
POST /reservations/{id}/approve     -> OP-05 Approve
POST /reservations/{id}/reject      -> OP-06 Reject
POST /reservations/expire-due       -> OP-07 Expire (also runs on a timer)
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

**Status after C03: runnable and checked automatically.**
- Path: `ReservationController.create` → `ReservationService.create` → `ReservationRepository`.
- Automated check: `CP1WalkingSkeletonTest` starts the application on a random port and sends a real
  HTTP `POST /reservations`. It asserts `201` + `id` + `state = DRAFT` and then loads the
  reservation from the database under that id (court, user, interval, state). The negative
  case `start == end` must return `400 INVALID_INTERVAL` and persist nothing.
- Runs in the normal build (`mvn test`), or alone: `mvn test -Dtest=CP1WalkingSkeletonTest`.
  Transcript: [`docs/evidence/cp1-walking-skeleton-run.log`](docs/evidence/cp1-walking-skeleton-run.log).

## Repository layout
```
README.md
docs/
  intent-and-change.md            # Project Frame (C01) + C08 Change Card CR-01
  architecture-and-decisions.md   # stack rationale, ADRs, future pressure (C01)
  evidence-and-evolution.md       # C01 spike ... C07/C08 risks, C08 test cases, C09 release record, C10 operations
  c01-engineering-spike.md        # C01 change + review-cycle record
  c02-baseline-v0.1.md            # Specification Baseline v0.1 (accepted)
  c02-change-v0.2-approval.md     # approval change: impact analysis -> Baseline v0.2
  c02-evidence.md                 # C02 evidence, mismatches, architecture drivers
  c03-architecture.md             # C03: drivers, ADR-6/7, views, delta, verification
  evidence/
    spike-A-persistence-run.log             # C01 spike run output
    c02-baseline-v0.1-verification.log      # v0.1: 34/34 green
    c02-baseline-v0.2-verification.log      # v0.2: 62/62 green
    c02-baseline-v0.2-demo-run.log          # running app, 28 checks, 0 failed
    c02-req04-control-run-without-serialisation.log  # control: 3 double-bookings
    c03-as-is-lifecycle-race.log            # AS-IS: approve+reject both succeed (F-A4)
    c03-to-be-verification.log              # TO-BE: 67/67 green
    c03-to-be-demo-run.log                  # TO-BE running app: 28 checks, 0 failed
    c03-court-hold-control-run.log          # control: no hold -> 8 / 2 double-bookings
    c03-architecture-rule-control-run.log   # control: bypass -> ArchUnit fails the build
    cp1-walking-skeleton-run.log            # CP1: POST /reservations over HTTP, 2/2 green
    c08-cr01-baseline-run.log               # C08: TC-C08-01/02 on the before-baseline, 4/4
    c09-impact-prediction-run.log           # C09: only the 3 predicted tests fail before test updates
    c09-cr01-postgres-run.log               # C09: HTTP integration tests on PostgreSQL 16, 16/16
    c09-verify-run.log                      # C09: scripts/verify.sh --postgres at 399d5ef
    c09-clean-checkout.log                  # C09: fresh clone, clean verify, JAR smoke start
    c10-operations-transcript.log           # C10: success, conflict, DB outage, restart (HTTP + SQL)
    c10-operations-log-excerpts.log         # C10: matching application log lines
.github/workflows/ci.yml           # C09 CI: build + tests + JAR hash; PostgreSQL integration job
scripts/verify.sh                  # C09: the same checks without CI
src/
  main/java/org/example/reservation/   # config, demo, domain, repository, service, web
  main/resources/                      # application(-postgres|-demo).properties
  test/java/org/example/reservation/   # v0.1 + v0.2 suites, C03 race + ArchUnit tests, CP1 skeleton, spike
```
