# C01 Engineering Spike

**Spike chosen:** A — Persistence.

## Question / unknown
Does a `Reservation`, together with its related `Court` and `AppUser`, actually
survive a real round-trip through the database and our JPA mappings — i.e. can we
persist it and then load it back with all fields and relationships intact, not
just from Hibernate's in-memory cache?

## What we did
We wrote an executed JUnit test, `ReservationPersistenceSpikeTest`
(`src/test/java/org/example/reservation/ReservationPersistenceSpikeTest.java`),
run with `@DataJpaTest` against a real database engine (H2, `MODE=PostgreSQL`):

1. Persist a `Court`, an `AppUser` and a `DRAFT` `Reservation`.
2. `em.flush()` to force the INSERTs to the database, then `em.clear()` to empty
   the persistence context so nothing can be served from the first-level cache.
3. Reload the reservation by its generated id (`em.find`) in a fresh unit of work
   and assert every field and both relationships.

Run with the IntelliJ-bundled Maven 3.9.9 + JDK 21:
`mvn test -Dtest=ReservationPersistenceSpikeTest`.
Full console output is committed at
[`evidence/spike-A-persistence-run.log`](evidence/spike-A-persistence-run.log).

## Observed result
The test **passed** (`Tests run: 1, Failures: 0, Errors: 0` → `BUILD SUCCESS`).
The log shows the real SQL round-trip:

```
Hibernate: insert into court (closing_time,location,name,opening_time,type,id) values (?,?,?,?,?,default)
Hibernate: insert into app_user (email,full_name,id) values (?,?,default)
Hibernate: insert into reservation (court_id,created_at,end_time,start_time,state,user_id,id) values (?,?,?,?,?,?,default)
-- after flush() + clear() --
Hibernate: select r1_0.id, ... from reservation r1_0 join court c1_0 on ... join app_user u1_0 on ... where r1_0.id=?
```

The reloaded reservation kept its id, `state = DRAFT`, `startTime`/`endTime`,
`createdAt`, and its `Court` (name + opening time) and `AppUser` (email)
relationships — proving the mapping and the DB round-trip work.

The full test suite (persistence spike + 5 business-rule/operation tests) is also
green: `Tests run: 6, Failures: 0, Errors: 0`.

## Decision / what changes because of the result
- **The JPA mapping and persistence layer are validated** — we keep the current
  entity design (`Court`, `AppUser`, `Reservation`, `ReservationState`) and Spring
  Data JPA repositories as the foundation for CP1.
- We keep **H2 (`MODE=PostgreSQL`) for dev/tests and PostgreSQL for prod**; the
  spike ran with no external DB, which keeps the build reproducible for every team
  member.
- The spike surfaced the real follow-up risk: persistence works, but the
  *check-then-act* overlap rule is not yet safe under concurrency. That is
  recorded as our selected future pressure (Q / Scale) in
  [`architecture-and-decisions.md`](architecture-and-decisions.md) and is the next
  thing to harden with a DB-level constraint.

---

## Evidence C02: specifikace → běžící aplikace

Short form of the C02 evidence. The full version, with every table, is in
[`c02-evidence.md`](c02-evidence.md) (section numbers below refer to it).

**Přijatá baseline (accepted baseline):**
- **v0.1:** [`c02-baseline-v0.1.md`](c02-baseline-v0.1.md), accepted by the team in §9.3. It contains BR-01…BR-07, REQ-01…REQ-08 and OP-01…OP-04, plus the use-case view, the statechart and the activity flows.
- **v0.2:** [`c02-change-v0.2-approval.md`](c02-change-v0.2-approval.md), accepted by the team in §9. It adds BR-08…BR-11, REQ-09…REQ-15 and OP-05 Approve / OP-06 Reject / OP-07 Expire.

**Předvedené základní operace (core operations demonstrated):** all four core operations
(create, availability, confirm, cancel) and the three approval operations, demonstrated two ways (§2):
- the verification suites: `mvn test` → 62/62 at `baseline-v0.2`;
- a scripted run of the real application: `mvn spring-boot:run -Dspring-boot.run.profiles=demo` → 28 checks, 0 failed.

**Skutečně provedené příklady ověření (verification examples actually executed):** at least one success and one
negative/boundary example per operation, each test named by its V-id (§3). Examples:
- OP-01: V-01.1 valid → DRAFT / V-01.3 `start == end` → rejected;
- OP-02: V-02.3 `[11:00,12:00)` next to a CONFIRMED `[10:00,11:00)` → AVAILABLE / V-02.4 → CONFLICT;
- OP-03: V-03.1 → CONFIRMED / V-03.2 overlap → rejected, stays DRAFT;
- concurrency: V-03.9, 8 parallel confirms → exactly 1 CONFIRMED;
- OP-04: V-04.1 before start → CANCELLED / V-04.4 `now == start` → rejected;
- OP-05: V-05.1 → CONFIRMED / V-05.4 `now == deadline` → EXPIRED.

A control run without the per-court serialisation produced 3 CONFIRMED reservations for one slot
([`evidence/c02-req04-control-run-without-serialisation.log`](evidence/c02-req04-control-run-without-serialisation.log)).
This shows the concurrency test really detects a double-booking.

**Nalezený nesoulad a způsob vyřešení (mismatches found and how they were resolved):** four (§4), each fixed in the source where the defect actually was:
- **M-1:** availability disagreed with confirm. Defect in the **specification**; REQ-02 was widened and a reason code added.
- **M-2:** the lazy expiry write was rolled back together with the thrown error. Defect in the **implementation**; the expiry now commits in its own transaction, which became driver AD-8.
- **M-3:** OP-07 counted reservations left by other examples. Defect in the **verification example**; each example now starts from a known set.
- **M-4:** the demo script used the wrong owner. Defect in the **demonstration**.

**Shrnutí dopadu změny (change impact summary):** the analysis was written against the frozen `baseline-v0.1` tag,
before any edit (change doc §2).
- **Affected:** OP-03 (on a gated court the target state becomes `PENDING_APPROVAL`), REQ-03, OP-04/REQ-05/BR-03.1
  (`PENDING_APPROVAL` can be cancelled), OP-02/REQ-02 (approval disclosure added), REQ-04 → REQ-15, the statechart,
  the use-case view and the Notification interface.
- **Unaffected, with reasons:** OP-01, BR-01, BR-02 (the blocking set is still exactly `{CONFIRMED}`), BR-04, BR-06,
  REQ-06 and REQ-07.
- **New:** the Approver actor and the operations OP-05/06/07; the states `PENDING_APPROVAL`, `REJECTED` and `EXPIRED`.

**Zbývající předpoklad / neznámá (remaining assumption / unknown):** A-1 says identity is asserted (`X-Actor-Id`), not authenticated.
A-7 says the approval window (24 h) has no value from any stakeholder. The full list A-1…A-12 is in §6.

**Architektonické drivery přenesené do C03 (architecture drivers carried into C03):** AD-1…AD-8 (§7). The primary one is
**AD-1 + AD-6**: BR-02 under concurrency, now enforced in two separate service methods (confirm and approve).

**Commit / tag aplikace (application commit / tag):** `baseline-v0.1` (`2eae622`, 34/34) and `baseline-v0.2` (`672c20d`, 62/62).

---

## C03 — Architecture Evidence

Full work: [`c03-architecture.md`](c03-architecture.md) (sections A–L referenced below).

**Baseline:** v0.2 — [`c02-change-v0.2-approval.md`](c02-change-v0.2-approval.md), application at tag `baseline-v0.2` (`672c20d`).

**Part A:** [`architecture-and-decisions.md` § C03 Part A](architecture-and-decisions.md#c03-part-a--as-is-realisation-of-one-scenario)
(A1–A8) traces OP-03 on a gated court → OP-05 through the code at `672c20d`. It covers the
step-to-code mapping, the E10 conflict path, three v0.2 ↔ code mismatches, the BR-02
enforcement points (decided in two methods, performed by two setters), dependencies, and the
AS-IS diagram. The question it carries forward became decision question D. Condensed into
findings F-A1…F-A6 in `c03-architecture.md` §A. The decisive one is **F-A4**: approve + reject
(or approve + cancel) of the *same* pending request both succeed, the database keeps the last
writer (`CONFIRMED`), and the owner is told both outcomes. This was proven by an executed test
before any code changed: [`evidence/c03-as-is-lifecycle-race.log`](evidence/c03-as-is-lifecycle-race.log).

**Drivers:** (§B)
- **DR-1** BR-02 under concurrency, whichever operation allocates (AD-1 + AD-6).
- **DR-2** one decision owner per lifecycle transition, atomic per reservation (AD-4 + F-A4).
- **DR-3** delayed approval: pending state outlives its request and ends by a human or by time (AD-5 + AD-8).
- **DR-4** notification may fail, and must never announce an uncommitted outcome (C01 ADR-3 + F-A5).

**Decision question:** (§D) where should the allocation decision — the transition into
`CONFIRMED` — be enforced so that BR-02 stays true under concurrency for every operation that
can perform it?

**Alternatives:** (§E1, §E2)
- **A:** one application element, *Court Allocation*, owns the per-court hold, the allocation guards and the only path into `CONFIRMED`.
- **B:** a PostgreSQL exclusion constraint owns BR-02, and operations translate SQLSTATE `23P01`.

B gives the stronger guarantee against foreign writers. A keeps the specified conflict outcome
with the rule owner and keeps BR-02 testable on H2 without Docker.

**Scenario walkthrough:** (§E3) confirm on a gated court → request waits → two overlapping
approvals race (V-05.9), and approve vs reject of the same request (V-06.5). Both alternatives
realise V-05.9 on PostgreSQL, but **only A on the test database**. **Neither** solves V-06.5,
which shows that the same-row race needs a decision of its own (ADR-7).

**ADR:** (§F)
- **ADR-6** — Alternative A (Court Allocation funnel). Accepted negatives: writers outside the application are unprotected, and allocations on one court are serialised. Reconsider when there is a second writer, measurable lock wait, or PostgreSQL-only tests (then *add* B).
- **ADR-7** — optimistic `@Version` per reservation + statechart edge table in `ReservationState` + notifications after commit + one per-reservation expiry transaction. The court-hold alternative was rejected because it would deadlock lazy expiry.

**Views:** all Mermaid, in [`c03-architecture.md`](c03-architecture.md):
- domain class — §C1 (8 concepts incl. `ApprovalRequest`/`ApprovalDecision`, 7 invariants)
- context — §G1
- static architecture — §G2 (6 elements, owners, allowed dependencies)
- state ownership — §G3 (every §7b edge: decision owner vs requester)
- runtime/deployment — §G4 (one JVM process, scheduler thread, H2/PostgreSQL, Notification Service)
- design sequence — §H1 (confirm → pending → later approve, `alt` conflict, version check at commit)
- focused design class — §H2 (11 classes/interfaces, every H1 message owned)

**Cross-view issues found/resolved:** seven (§I), all resolved in the artifacts before code changed.
- **X-1** C02 REQ-11/REQ-12 gates claimed court-hold serialisation that neither design nor code had → ADR-7 + erratum notes in the C02 change doc.
- **X-2** expiry had two owners → one `ApprovalExpiry`.
- **X-3** the H1 draft bypassed Court Allocation.
- **X-4** availability lacked allowed dependencies.
- **X-5** `CourtHold` as proof of the hold.
- **X-6** atomicity of statechart edges had no owner.
- **X-7** notification had no element.

**AS-IS → TO-BE delta:** (§J) 7 × CHANGE, 5 × KEEP, 3 × VERIFY.

**Implementation changes:** (§K)
- new `CourtAllocation`, `CourtHold`, `ApprovalWorkflow`, `NotificationDispatcher`;
- `LazyApprovalExpiry` → `ApprovalExpiry`;
- `ReservationService` reduced to the Reservation Lifecycle (create, availability, confirm, cancel);
- `@Version` on `Reservation`, and an edge table in `ReservationState`;
- the controller routes approval operations to `ApprovalWorkflow` and maps version conflicts to 409 `INVALID_STATE`;
- REST surface and specification behaviour are unchanged.

**Behaviour verification:** (§L1)
- `mvn clean test` → **67 / 67** ([`evidence/c03-to-be-verification.log`](evidence/c03-to-be-verification.log)), which includes every v0.1/v0.2 example unchanged in V-id and assertion.
- V-06.5 / V-04.11: **fail AS-IS → pass TO-BE**.
- Demo run of the application → **28 checks, 0 failed** ([`evidence/c03-to-be-demo-run.log`](evidence/c03-to-be-demo-run.log)).
- Control run with the hold removed from `CourtAllocation`: V-03.9 confirms **8**, V-05.9 **2** ([`evidence/c03-court-hold-control-run.log`](evidence/c03-court-hold-control-run.log)).

**Architecture conformance rule + result:** (§L2)
- *Rule:* only Court Allocation may move a Reservation into `CONFIRMED` or take the court hold.
- *Check:* `C03ArchitectureRuleTest` (ArchUnit, runs in `mvn test`).
- *Result:* **pass 3/3**.
- *Control run:* a deliberate bypass in `ApprovalWorkflow` fails the build and names `ApprovalWorkflow.java:116` ([`evidence/c03-architecture-rule-control-run.log`](evidence/c03-architecture-rule-control-run.log)).

**Remaining uncertainty / risk:**
- BR-02 is enforced only for writes through this application (ADR-6). A manual SQL update can double-book. Adding the exclusion constraint later (Alternative B as defence in depth) needs PostgreSQL in the test path.
- All allocations on one court are serialised. Under the C01 "10× members" pressure this shows up as lock wait on popular courts. It has not been measured.
- Notifications are at most once: a crash between commit and dispatch loses the message, and there is no retry. Acceptable while the boundary is a logging adapter. A real provider needs an outbox (ADR-7 "reconsider").
- The ArchUnit rule protects the transition *methods*. Code using reflection, or a native SQL `UPDATE … SET state = 'CONFIRMED'`, would not be caught.
- The `version` column is `NOT NULL` and is not migrated for existing PostgreSQL rows. No production data exists yet.
- Identity is still asserted (`X-Actor-Id`, A-1/AD-3). BR-05/BR-09 ownership is only as strong as that header.

**Commit/tag:**
- tag **`c03-architecture`** (`5f9c424`): the architecture document, the code changes and the logs above;
- tag **`c03-part-a`** (`c177da8`): adds the Part A trace to `architecture-and-decisions.md` and the Part A paragraph of this section. No code changed.

---

## CP1 walking skeleton — evidence

**Path (README):** `POST /reservations` → validate → persist → return reservation ID → automated check.

**Automated check:** `CP1WalkingSkeletonTest`
(`src/test/java/org/example/reservation/CP1WalkingSkeletonTest.java`). It starts the full application on a
random port and sends real HTTP requests. Unlike the specification suites, it does not call the services directly.
- **Success:** a valid request returns `201` with `id` and `state = DRAFT`. The reservation is then loaded from the
  database under that id, and its court, user, interval and state match the request.
- **Validation:** `start == end` returns `400 INVALID_INTERVAL`, and the number of reservations does not change.

**Result:** `Tests run: 2, Failures: 0` → `BUILD SUCCESS`. The transcript in
[`evidence/cp1-walking-skeleton-run.log`](evidence/cp1-walking-skeleton-run.log) shows the embedded server start, the `INSERT` and the reload by id.
Full suite with the test: `mvn clean test` → **69 / 69**.

---

## C05 DD Verification Evidence

**Unit:** Court Allocation (C03 R2, ADR-6). Detailed design:
[`architecture-and-decisions.md` § C05 Detailed Design](architecture-and-decisions.md#c05-detailed-design).
The DD was written first, against commit `3cc1114`. The code was then compared with it and
changed where it did not follow it (see the mismatch table below).

### Tests derived from DD

17 new tests: 15 unit tests in `service/CourtAllocationTest` (no Spring, repositories mocked)
and 2 component tests in `C05CourtAllocationComponentTest` (Spring context + H2).

| DD element / decision | What must hold? | Test level | Test case |
| --- | --- | --- | --- |
| **Algorithm**: decision table, row order (rule 2 before 3 before 4) | a slot that breaks several rules gets the reason of the **first** one, and no DB query runs if a static rule already failed | unit | `DecisionTable.row2_inactiveWinsOverLaterRules`, `row3_outsideHoursWinsOverConflict` |
| **Algorithm**: every row of the table | each row yields its `Reason`. Edges: `start == end`, `start > end`, `null`, crossing midnight, exactly `08:00–22:00` is inside | unit | `DecisionTable.row1…row5` |
| **Required-interface contract** `findOverlapping` (DR-3) | the query receives exactly `ReservationState.blockingStates()` = `{CONFIRMED}` and the interval unchanged, **once** | unit | `Predicate.predicateUsesTheSingleBlockingSet` |
| **State transition** `DRAFT → CONFIRMED`, `PENDING_APPROVAL → CONFIRMED` (DR-5) | the target state is reached, the decision (`decidedBy`, `decidedAt`) is recorded, and `save` is called once | unit | `Transitions.confirm_draftBecomesConfirmed`, `confirmApproved_pendingBecomesConfirmedWithDecision` |
| **Failure behaviour**: business outcome (DR-4, DR-6) | refusal → the specified code, the state is unchanged, **no half-written decision**, nothing saved | unit | `Transitions.confirm_conflictChangesNothing`, `confirmApproved_inactiveCourtChangesNothing`, `requireAdmissible_outsideHoursCode` |
| **Failure behaviour**: defect vs business outcome | a wrong source state or a hold on another court → an unchecked JDK exception, **not** a `ReservationException`, and nothing changes | unit | `Transitions.confirm_wrongSourceStateIsADefect`, `Hold.holdOnAnotherCourtIsRefused` |
| **Interface contract** `hold` (DR-1) | it locks the reservation's own court; a missing court → `COURT_NOT_FOUND`; **no transaction → refused** | unit + component | `Hold.hold_locksTheReservationsCourt`, `hold_missingCourt`; `C05CourtAllocationComponentTest.holdWithoutTransactionIsRefused` |
| **Collaboration**: one decision table, two consumers (DR-2) | for the same slot, OP-02 (advisory, no hold) and OP-03 (binding, under the hold) give the **same** verdict on every row, including a slot that breaks rules 2, 3 and 4 at once | component | `C05CourtAllocationComponentTest.availabilityAndConfirmAgreeOnEveryRow` |
| **Concurrency** (DR-1 under load) | at most one `CONFIRMED` per overlapping slot, for confirm and approve alike | integration (existing, re-executed) | V-03.9, V-05.9 in `BaselineV01/V02…Test`; control run from C03 |

**Two tests in detail**

*`DecisionTable.row2_inactiveWinsOverLaterRules`*
- **Selected level:** unit. `CourtAllocation` is constructed directly. `ReservationRepository` is a Mockito mock that would report a conflict if it were asked.
- **Why this level:** the decision being tested is *order and short-circuit*. Only a mock can show that the DB rule was **not evaluated** (`verifyNoInteractions`). With a real database, the test could only see the returned reason, not whether the query ran. The test needs no Spring and no H2 and runs in milliseconds.
- **Design decision verified:** the decision table is evaluated top-down, the first failing rule wins, and the only DB rule is last. This is what makes OP-02 and OP-03 report the same reason, and what saves a query for statically refused slots.

*`C05CourtAllocationComponentTest.availabilityAndConfirmAgreeOnEveryRow`*
- **Selected level:** component, with the real Spring context, transaction proxies and H2. `ReservationService` is called, not `CourtAllocation`.
- **Why this level:** the decision is about a **collaboration**. Two callers of the unit (the advisory OP-02 path without a hold and the binding OP-03 path with one) must get the same answer. A unit test of `assess` alone cannot show that both callers actually use it. That is exactly the gap the code had at `3cc1114` (mismatch C05-M1).
- **Design decision verified:** DR-2, *one decision table, two consumers*. The row-2 slot is inactive, outside hours *and* conflicting at once, so the test also checks that both paths use the same rule order.

### Unit/component tests executed

- `mvn clean test` → **86 / 86**, `BUILD SUCCESS` (69 before C05, plus the 17 above). Transcript: [`evidence/c05-dd-verification.log`](evidence/c05-dd-verification.log).
- The scripted demo of the real application, `mvn spring-boot:run -Dspring-boot.run.profiles=demo` → `RESULT: 28 checks, 0 failed`. It is unchanged from C03 and covers OP-02 through the real app.
- **Control run** (do the derived tests detect a violated DD?): `assess` was mutated so that the BR-02 rule runs *before* BR-07/BR-04. Result: **4 of 17 fail**: `row2…`, `row3…` (expected `COURT_INACTIVE` / `OUTSIDE_OPENING_HOURS`, got `CONFLICT`), `predicateUses…` (query ran twice), and the component test on its row-2 slot. The mutation was reverted afterwards. Transcript: [`evidence/c05-dd-control-run.log`](evidence/c05-dd-control-run.log). The control run was made before the C05-M2 fix, which changed `requireAdmissible` only. `assess` is identical in both versions.

### Design ↔ implementation mismatch

| DD decision | Implemented at `3cc1114`? | Evidence | Action |
| --- | --- | --- | --- |
| DR-1 hold with a proof token, only in the caller's transaction | YES | `CourtAllocation.hold` (`MANDATORY`), `CourtHold` with a package-private constructor | keep |
| **DR-2 one decision table, used by OP-02 *and* by confirm/approve** | **PARTLY (C05-M1)**: only rule 4 (`isFree`) was shared. `ReservationService.checkAvailability` had its **own copy** of rules 1–3 (`git show 3cc1114:…/ReservationService.java`, lines 111–125), and `requireAdmissible` had a second copy of rules 2–3. The two copies agreed only by convention. This is the same drift C02 M-1 had already produced once. | `CourtAllocation.assess` (`:61`); `ReservationService.checkAvailability` now delegates (`:114`); `availabilityAndConfirmAgreeOnEveryRow` | **update code**. The DD is right: C03 §C2 already required that R8 use the predicate R2 enforces. The code had implemented that requirement only for BR-02. |
| Rule order BR-01 → BR-07 → BR-04 → BR-02, DB rule last | YES in both copies, but only by convention | `DecisionTable.row2/row3`; control run | keep; it is now protected by the tests |
| DR-3 blocking set taken only from `ReservationState` | YES | `isFree`; `Predicate.predicateUsesTheSingleBlockingSet` | keep |
| **DR-4 mapping `Reason → code` is 1:1 and compile-checked** (variation point "new rule") | **NO (C05-M2)**. This mismatch was found in the *first C05 implementation*, not in the old code. `requireAdmissible` used a switch *statement*, and Java does not require those to be exhaustive over an enum. A new `Reason` without a `case` would have fallen through, and the allocation would have **proceeded**. | `CourtAllocation.requireAdmissible` (`:87`), now a switch *expression* | **update code**. The DD's variation point ("the compiler refuses the build until the new `Reason` is mapped") is the intent. The DD text was made explicit in "Important methods". |
| DR-5 transition only after the guard; the entity checks the edge | YES | `Transitions.*` | keep |
| DR-6 a refusal changes nothing (no half-written decision) | YES: the guard runs before `Reservation.approve` sets `decidedBy` | `confirmApproved_inactiveCourtChangesNothing` | keep |
| Business outcome vs defect | YES | `confirm_wrongSourceStateIsADefect`, `holdOnAnotherCourtIsRefused` | keep |
| Precondition "the hold was obtained in **this** transaction" | **PARTLY**: `CourtHold` does not know its transaction. A hold kept beyond its transaction (e.g. stored in a field) would still be accepted. | — | **keep the DD, do not change the code**. Only `service`-package code can construct a hold, and ArchUnit restricts who calls the transitions. Binding the hold to a transaction id would add state and complexity for a misuse that no current caller can commit. Recorded as a remaining risk. |

### Resolution

- **C05-M1**: the code followed the design. `CourtAllocation.assess` is now the only place the admissibility rules live. OP-02 calls it without a hold, and `requireAdmissible` calls it under the hold. `checkAvailability` lost its own rule copy. It keeps the REQ-13 disclosure and counts pending requests only for a valid interval, as before. All C02 verification examples (`BaselineV01`/`V02` suites, 61 tests) and the demo pass unchanged. One behaviour change at the Java API: `checkAvailability` with a `null` bound now returns `INVALID_INTERVAL` instead of throwing a `NullPointerException`. This cannot happen over HTTP, where both bounds are required request parameters.
- **C05-M2**: the code followed the design (an exhaustive switch expression). The DD was made more explicit, but its intent did not change.
- **Hold bound to its transaction**: the design was kept and the gap accepted, see the risks below.

### Remaining design risk

- **Leaked hold**: see the last row of the mismatch table. This is mitigated by package-private construction and the ArchUnit rule, not by a runtime check.
- **`Reason` serves two purposes.** It is OP-02's public response type and also the unit's internal verdict. A future reason that only availability needs (e.g. "pending requests exist") would have to be kept out of `assess`, or it would become a binding refusal. Reconsider splitting the types when the first such reason appears.
- **Lock wait** under the C01 "10× members" pressure is still unmeasured. The DD makes the lock scope explicit (one court per transaction, taken before any reservation write) but does not change it.
- The DD inherits the C03 risks: BR-02 is guaranteed only for writes through this application, and identity is only asserted (`X-Actor-Id`).

### Commit/tag

Tag **`c05-detailed-design`**. It contains the DD section, the code change (`CourtAllocation`,
`ReservationService`), the 17 tests and the two logs above.
