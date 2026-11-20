# Project Frame

## Reservation domain
We reserve **sports courts** in a sports facility (e.g. tennis, squash, badminton,
basketball or football courts). A reservation books one specific court for a
continuous time slot on a given day.

## Purpose
The system lets members of a sports facility book courts online and guarantees
that no two confirmed bookings collide on the same court. It exists to replace
ad-hoc booking (paper sheets, phone calls, double-booked courts) with a single
source of truth that staff and members can both trust.

## Users / Stakeholders
- **Member** — creates, confirms and cancels their own reservations.
- **Facility staff / administrator** — manages courts and their opening hours,
  oversees reservations.
- **Notification Service** — external stakeholder that delivers confirmation
  messages (see system boundary).

## Core concepts
- **Reservation** — a booking of a court by a user for `[startTime, endTime)`,
  with a lifecycle state.
- **Resource → Court** — the bookable court: name, type, location, opening hours.
- **User → AppUser** — the person who creates the reservation.
- *(supporting)* **ReservationState** — DRAFT / CONFIRMED / CANCELLED.

## Core operations
- **Create reservation** — register a new booking as `DRAFT`.
- **Confirm / approve reservation** — validate business rules and move
  `DRAFT → CONFIRMED`; then notify the user.
- **Cancel reservation** — move a booking to `CANCELLED`, freeing the court.
- **Check availability** — is a court free (no confirmed overlap) and open at a
  given time?

## Persistent state
- **Reservation:** id, court, user, startTime, endTime, state, createdAt.
- **Court:** id, name, type, location, openingTime, closingTime.
- **AppUser:** id, fullName, email.

## State-changing operation
`DRAFT → CONFIRMED` (via **confirm**). Only a `DRAFT` may be confirmed; confirming
runs both business rules and, on success, triggers a notification.
Also `DRAFT/CONFIRMED → CANCELLED` (via **cancel**).

## Common business rule
Two **CONFIRMED** reservations of the **same court** must not overlap in time.
Overlap is evaluated on half-open intervals `[start, end)`, so a booking ending
at 11:00 and one starting at 11:00 do **not** clash. `DRAFT` and `CANCELLED`
reservations do not block the court.

## Domain-specific business rule
**Opening-hours rule:** a reservation must lie entirely within its court's
opening hours (e.g. 08:00–22:00) and within a single calendar day. A court cannot
be confirmed for a slot that starts before opening, ends after closing, or spans
midnight. *(C01: enforced in `ReservationService.confirm` and `isAvailable`. Since C03
(ADR-6) the rule is enforced by `CourtAllocation` on confirm and approve. `ReservationService.checkAvailability`
reports it as `OUTSIDE_OPENING_HOURS`. It is specified as BR-04 in the C02 baseline.)*

## External / system boundary
**Notification Service.** When a reservation is confirmed, the system calls the
`NotificationService` boundary to inform the member. The boundary is an interface
(`NotificationService`) with a default implementation (`LoggingNotificationService`),
so the real provider can be swapped later. A notification failure/timeout is
caught and logged and does **not** roll back a valid confirmation.

## Assumption
We assume all courts and users operate in a **single time zone** (facility-local
time), so `LocalDateTime` slots are unambiguous and comparable without offset
conversion.

## Unknown
We do not yet know whether members will be allowed to hold **multiple concurrent
reservations** across different courts, or whether a fair-use quota (e.g. max
hours per member per day) will be required. This may add a second domain rule
later.

---

## C08 / CR-01 — Controlled Change Card: cancellation notice period

**Request source and concrete project need.** Facility staff / administrator (stakeholder in the
Project Frame above). A member who cancels a CONFIRMED court a few minutes before the start leaves
the court empty: nobody else can realistically book it any more. The facility asks: *"A member must
not be able to cancel a confirmed court at short notice; cancel at least 2 hours before the start."*
This closes the open assumption **A-5** of Baseline v0.1 (`docs/c02-baseline-v0.1.md` §10), which
the team deliberately left open in C02 (finding C-10: no stakeholder had supplied a figure). The
figure **2 h** is the requester's value, **confirmed by the team on 2026-11-20**; it is configuration,
not a constant (same treatment as the approval window, A-7).

**Before baseline.** commit `b558fc1` (master; = tag `c08-final` `07aacec` + two docs/comment-only
commits) · spec: BR-03 @ `c02-baseline-v0.1.md` (BR-03.1 amended by `c02-change-v0.2-approval.md`),
REQ-05, A-5 · architecture: ADR-6 / ADR-7 (`c03-architecture.md`); OP-04 is owned by the
Reservation Lifecycle (`ReservationService.cancel`) · schema: Hibernate `ddl-auto` from the entity
mapping at that commit, no migration scripts · runtime: Java 21.0.4, Spring Boot 3.5 · test DB:
isolated in-memory H2 (`MODE=PostgreSQL`, `create-drop`) · command: `mvn clean test` → 90/90 at
`c08-final`. No secrets stored. Executed baseline test cases: TC-C08-01/02 in
[`evidence-and-evolution.md`](evidence-and-evolution.md#c08--controlled-change-test-cases-cr-01).

**Old accepted behaviour / observable limitation.** BR-03.2: cancellation is allowed while
`now < start` (strict). A CONFIRMED booking can be cancelled one second before it starts
(observed: TC-C08-02, cancel 1 h before start → `200 CANCELLED`).

**New intended behaviour, scope, out of scope.**
- *In scope:* a **MEMBER** cancelling **their own CONFIRMED** reservation must do so while
  `now < start − noticePeriod`; otherwise the cancel is refused and the reservation stays CONFIRMED.
  `noticePeriod` is a configuration value (requested 2 h).
- *Unchanged on purpose:* DRAFT and PENDING_APPROVAL reservations do not hold the court (BR-02,
  decision D-2), so cancelling them late leaves no court empty → still `now < start`. STAFF keep
  `now < start` (operational cancellations, e.g. a court closed for repair; BR-05 staff role).
  Idempotent repeated cancel (BR-03.3 / REQ-06) is unchanged.
- *Out of scope:* no-show penalty or fee, a "late cancellation" flag, notifications about the rule,
  UI. (A-5 also mentions a no-show penalty — not requested now, stays open.)

**Acceptance conditions.** (`N` = notice period, fixture `N = 2 h`, booking `[12:00,13:00)`)

| AC | Kind | Condition |
| --- | --- | --- |
| AC-01 | positive | Member cancels own CONFIRMED with `now < start − N` (e.g. 09:00, 09:59:59) → `CANCELLED`, record retained, slot free again. |
| AC-02 | negative | Member cancels own CONFIRMED with `start − N < now < start` (e.g. 11:00) → refused `TOO_LATE_TO_CANCEL` (HTTP 400), committed state still `CONFIRMED`, slot still blocked. |
| AC-03 | boundary | `now == start − N` exactly (10:00:00) → **refused** (strict, same convention as BR-03.2 `now == start`). |
| AC-04 | regression | STAFF cancel at 11:00 → `CANCELLED`; member cancel of DRAFT / PENDING_APPROVAL at 11:00 → `CANCELLED`; `now ≥ start` still refused for everyone; repeated cancel of CANCELLED still succeeds; BR-02 (no two overlapping CONFIRMED) still holds. |
| AC-05 | config | `N` is read from `reservation.cancellation.notice-period` (ISO-8601 duration); `PT0S` reproduces the old behaviour exactly. |

**Impact analysis method / evidence reviewed.** Started from AC-02, found the owner of the decision
(`ReservationService.cancel`, Reservation Lifecycle; C03 statechart ownership table G3), followed the
call chain HTTP `POST /reservations/{id}/cancel` → `ReservationController.cancel` →
`ReservationService.cancel` → `Reservation.cancel` → `ReservationRepository.save`, then searched all
callers of `cancel(` in `src/main` and `src/test` (`grep -rn "cancel("`) and every document that
states BR-03.2.

| Dimension / exact artifact | CHANGE / CHECK / NO IMPACT + why | Needed delta / verification | Owner / evidence |
| --- | --- | --- | --- |
| Spec: BR-03.2, REQ-05, A-5 (`c02-baseline-v0.1.md`) | **CHANGE** — the time boundary for member + CONFIRMED moves to `start − N`; A-5 becomes closed. | Add clause BR-03.6, close A-5. | Nevoral; reviewed spec diff |
| State model (statechart `CONFIRMED → CANCELLED`) | **CHANGE** — guard of that edge gains `actor is STAFF ∨ now < start − N`. No new state. | Update guard text. | transition tests AC-01..03 |
| Architecture / ownership (ADR-6, ADR-7, G3) | **CHECK** — the edge stays owned by Reservation Lifecycle; Court Allocation is not involved (cancel allocates nothing). No new writer path. | Confirm the ArchUnit rule (`C03ArchitectureRuleTest`) still passes. | ArchUnit run |
| Detailed design / code: `ReservationService.cancel` | **CHANGE** — one guard after the existing BR-03.2 check; reads `now` from the injected clock once (BR-06). | New config value injected; message names the notice period. | spec/integration tests |
| Other writer paths: approval workflow, OP-07 sweep, `DataSeeder`, `SpecificationDemoRunner` | **CHECK** — sweep / approve / reject never write CANCELLED. Seeder and demo cancel bookings for *tomorrow* → always ≥ 2 h ahead. No admin / import path exists → NO IMPACT for those. | Run demo profile (`28 checks, 0 failed`). | demo log |
| API contract (`ReservationController`) | **CHECK** — reuses the existing error code `TOO_LATE_TO_CANCEL` → HTTP 400; only the message text differs. No new endpoint / field. | Integration test asserts code + status. | HTTP test |
| Data / schema / migration | **NO IMPACT** — no new column; the rule is evaluated at cancel time, stored rows are not reinterpreted (an already CANCELLED row stays CANCELLED). | — | — |
| Configuration | **CHANGE** — new property `reservation.cancellation.notice-period` in `application.properties` (default `PT2H`) and test config. Time zone unchanged (single facility zone, C01 assumption). | Document default + unit; app must start with the property. | config smoke (app start) |
| Tests | **CHANGE** — existing V-04.2 / V-04.8 cancel a CONFIRMED booking at 09:00 for 10:00 (1 h notice): they would now be refused. Their *intent* (cancel frees the slot / record retained) is unchanged → move their fixture time, not their oracle. New boundary tests for AC-01..05. TC-C08-02 expected result changes (it documents the old rule). | Adjust 2 fixtures, add new tests, rerun all. | `mvn clean verify` log |
| Release / recovery | **CHECK** — code rollback restores old behaviour; a refusal writes nothing, so rollback has no data consequence. | Note in C09 release record. | release record |
| Users / operation | **CHECK** — members get a refusal they did not get before; staff can still cancel on request. | Message must state the deadline. | AC-02 response body |

**Alternatives (common criteria).**

| Option | Meets AC? | Benefit | Cost / failure / rollback | Verification |
| --- | --- | --- | --- | --- |
| **A: notice-period guard in `ReservationService.cancel`** (config `N`, member + CONFIRMED only) | yes, AC-01..05 | one guard in the existing owner, no schema change, no new component; `N = PT0S` = old behaviour | members can no longer free a court late even for a valid reason → staff must do it; rollback = revert commit, no data impact | boundary tests 09:59:59 / 10:00 / 11:00, HTTP + DB oracle, full regression |
| **B: allow late cancel but record it** (new `lateCancellation` flag / no-show statistics, penalty later) | **no** — AC-02 not met, the court is still freed too late | keeps member freedom; gives data for a later penalty decision | schema change (new column), reporting, a policy nobody asked for yet; court still ends up empty | migration test, flag set at boundary |
| **C: NO_CHANGE — house rule + member communication** (notice on the website) | **no** — nothing enforces AC-02 | zero code | relies on members reading rules; the reported problem remains | nothing to verify in the system |

**Inaction consequence.** Short-notice cancellations keep leaving courts empty; A-5 stays an open
unknown that every future cancellation question runs into.

**Peer reviewer and challenged assumption.** Assumptions challenged: (1) STAFF exemption — wanted,
or must staff also respect `N`? (2) DRAFT / PENDING_APPROVAL exemption. (3) boundary
`now == start − N` refused (strict).
Reviewer: **Pavel Valošek** · date: **2026-11-20** · outcome: **APPROVED, no change requested** —
all three assumptions kept as written; the 2 h figure confirmed by the team.
*Recorded by Rostislav Nevoral on the reviewer's behalf (the reviewer had no access to a PC that
day).*

**Decision.** **ACCEPT option A** — proposed by Rostislav Nevoral, 2026-11-20; **final** after the
peer review above (approved 2026-11-20). Reason: it is the only option that meets AC-02, it is contained in one guard of the
existing owner (the containment A-5 predicted in C02), and `N = PT0S` gives a no-code fallback.

**If ACCEPT — smallest change and tests to perform in C09.**
- Delta: `ReservationService.cancel` guard + `reservation.cancellation.notice-period` property;
  spec BR-03.6 + A-5 closed; V-04.2 / V-04.8 fixture time moved to keep their original intent.
- New oracles: 09:59:59 → CANCELLED; 10:00:00 → refused, CONFIRMED; 11:00 → refused, CONFIRMED;
  STAFF at 11:00 → CANCELLED; DRAFT / PENDING at 11:00 → CANCELLED; `PT0S` → old behaviour.
- Regression: TC-C08-01 (unchanged oracle), TC-C08-02 (oracle **changes** to refused — documented
  contract change), full suite incl. BR-02 race tests and the ArchUnit rule, demo profile.
- C09 hand-off: commit, integration run, clean checkout, CI, release decision.

**If NO_CHANGE / REJECT:** not applicable.

**Open uncertainty and next trigger.** The figure 2 h is confirmed (2026-11-20); if the facility
changes it later, it is a config-only change. A no-show penalty (rest of A-5) is not decided — trigger: a stakeholder asks for it.
