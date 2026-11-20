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
| DR-1 hold with a proof token, only in the caller's transaction | YES | `CourtAllocation.hold` (`MANDATORY`), `CourtHold` with a package-private constructor; since C06 also an ArchUnit rule on the constructor (C06-F3) | keep |
| **DR-2 one decision table, used by OP-02 *and* by confirm/approve** | **PARTLY (C05-M1)**: only rule 4 (`isFree`) was shared. `ReservationService.checkAvailability` had its **own copy** of rules 1–3 (`git show 3cc1114:…/ReservationService.java`, lines 111–125), and `requireAdmissible` had a second copy of rules 2–3. The two copies agreed only by convention. This is the same drift C02 M-1 had already produced once. | `CourtAllocation.assess` (`:61`); `ReservationService.checkAvailability` now delegates (`:114`); `availabilityAndConfirmAgreeOnEveryRow` | **update code**. The DD is right: C03 §C2 already required that R8 use the predicate R2 enforces. The code had implemented that requirement only for BR-02. |
| Rule order BR-01 → BR-07 → BR-04 → BR-02, DB rule last | YES in both copies, but only by convention | `DecisionTable.row2/row3`; control run | keep; it is now protected by the tests |
| DR-3 blocking set taken only from `ReservationState` | YES | `isFree`; `Predicate.predicateUsesTheSingleBlockingSet` | keep |
| **DR-4 mapping `Reason → code` is 1:1 and compile-checked** (variation point "new rule") | **NO (C05-M2)**. This mismatch was found in the *first C05 implementation*, not in the old code. `requireAdmissible` used a switch *statement*, and Java does not require those to be exhaustive over an enum. A new `Reason` without a `case` would have fallen through, and the allocation would have **proceeded**. | `CourtAllocation.requireAdmissible` (`:87`), now a switch *expression* | **update code**. The DD's variation point ("the compiler refuses the build until the new `Reason` is mapped") is the intent. The DD text was made explicit in "Important methods". |
| DR-5 transition only after the guard; the entity checks the edge | YES | `Transitions.*` | keep |
| DR-6 a refusal changes nothing (no half-written decision) | YES: the guard runs before `Reservation.approve` sets `decidedBy` | `confirmApproved_inactiveCourtChangesNothing` | keep |
| Business outcome vs defect | YES | `confirm_wrongSourceStateIsADefect`, `holdOnAnotherCourtIsRefused` | keep |
| Precondition "the hold was obtained in **this** transaction" | **PARTLY**: `CourtHold` does not know its transaction. A hold kept beyond its transaction (e.g. stored in a field) would still be accepted. | — | ~~keep the DD, do not change the code~~ (C05). **Revised in C06 (finding C06-F1): update code.** `CourtHold` now registers a marker with the owning transaction's synchronizations and `requireAdmissible` refuses a hold whose marker is not in the current transaction. Tests: `Hold.holdFromEndedTransactionIsRefused`, `C05CourtAllocationComponentTest.holdFromEndedTransactionIsRefused`, `holdInInnerTransactionIsRefused`. |

### Resolution

- **C05-M1**: the code followed the design. `CourtAllocation.assess` is now the only place the admissibility rules live. OP-02 calls it without a hold, and `requireAdmissible` calls it under the hold. `checkAvailability` lost its own rule copy. It keeps the REQ-13 disclosure and counts pending requests only for a valid interval, as before. All C02 verification examples (`BaselineV01`/`V02` suites, 61 tests) and the demo pass unchanged. One behaviour change at the Java API: `checkAvailability` with a `null` bound now returns `INVALID_INTERVAL` instead of throwing a `NullPointerException`. This cannot happen over HTTP, where both bounds are required request parameters.
- **C05-M2**: the code followed the design (an exhaustive switch expression). The DD was made more explicit, but its intent did not change.
- **Hold bound to its transaction**: at C05 the gap was accepted; at C06 it was closed in code (see C06 below).

### Remaining design risk

- ~~**Leaked hold**~~: closed in C06 by a runtime check (C06-F1) and an ArchUnit rule on the `CourtHold` constructor (C06-F3).
- **`Reason` serves two purposes.** It is OP-02's public response type and also the unit's internal verdict. A future reason that only availability needs (e.g. "pending requests exist") would have to be kept out of `assess`, or it would become a binding refusal. Reconsider splitting the types when the first such reason appears.
- **Lock wait** under the C01 "10× members" pressure is still unmeasured. The DD makes the lock scope explicit (one court per transaction, taken before any reservation write) but does not change it.
- The DD inherits the C03 risks: BR-02 is guaranteed only for writes through this application, and identity is only asserted (`X-Actor-Id`).

### Commit/tag

Tag **`c05-detailed-design`**. It contains the DD section, the code change (`CourtAllocation`,
`ReservationService`), the 17 tests and the two logs above.

## C06 — Owner review of the internal evidence assessment

The assessment was made at `ce3e589`. All three candidate findings were accepted and fixed;
none was rejected or kept as a risk.

| Finding | Disposition | Change | Evidence |
| --- | --- | --- | --- |
| **C06-F1** the hold is not bound to its transaction (C05 precondition "in THIS transaction" not enforced) | **ACCEPTED, fixed in code** | `CourtHold` registers a `TransactionSynchronization` marker with the transaction that took the lock. `requireAdmissible` (and so `confirm` / `confirmApproved`) calls `CourtHold.requireValidFor`, which refuses the hold with `IllegalStateException` (a defect, not a business outcome) unless the marker is among the **current** transaction's synchronizations. A transaction that has ended, an inner `REQUIRES_NEW` (the outer one is suspended) and another thread each see a different list. | unit `Hold.holdFromEndedTransactionIsRefused`; component `C05CourtAllocationComponentTest.holdFromEndedTransactionIsRefused`, `holdInInnerTransactionIsRefused` (real Spring transactions + H2; the reservation stays `DRAFT`) |
| **C06-F2** README expects 69 tests | **ACCEPTED, fixed in docs** | README now expects the current count. | `README.md` §Run the tests |
| **C06-F3** the `CourtHold` constructor is reachable by every class of the `service` package | **ACCEPTED, fixed by a conformance check** | Java cannot narrow a constructor to one other top-level class, so the rule is an ArchUnit rule: no class except `CourtAllocation` calls `new CourtHold(Court)`. The vacuity guard now also requires that `CourtAllocation` does call it. | `C03ArchitectureRuleTest.only_court_allocation_creates_the_hold_proof`, `court_allocation_is_the_path_that_exists` |

**Test run after the fixes:** `mvn clean test` → **90 / 90**, `BUILD SUCCESS` (86 at `ce3e589`, plus
1 unit, 2 component and 1 architecture test).

**Control run** (do the new tests detect the violated precondition?): the call to
`requireCurrentTransaction()` in `CourtHold.requireValidFor` was commented out. Result: **3 of 90 fail**,
exactly the three C06-F1 tests; the mutation was reverted.

**Remaining gap from the C06 assessment (§7):** none — the transactional lifetime of `CourtHold` is now
enforced at runtime and tested.

Fixes in `f7312a4`, their tests in `d6974b7`; tag **`c08-status`** contains both.

## C07/C08 — Risk Assessment

**Reporting baseline:** tag `c08-status` (`d6974b7`, 2026-11-01): C06 findings F1–F3 fixed,
`mvn clean test` 90 / 90. The C06 findings are reproduced against tag `c06-assessed` (`ce3e589`).
**Next review:** C08, 2026-11-03 12:30.

Classification before treatment: L × I (1–2 LOW, 3–4 MEDIUM, 6–9 HIGH). An actual residual is
**NOT VERIFIED** until the treatment has been carried out and its effect has evidence. A planned
treatment does not lower it.

| ID | Category | Initial L / I → priority | Strategy | Expected residual | Actual residual |
| --- | --- | --- | --- | --- | --- |
| R-01 | Release / Operation | 2 / 3 → **HIGH (6)** | MITIGATE (done 2026-11-01) | 1 / 3 → MEDIUM (3) | 1 / 3 → MEDIUM (3), verified |
| R-02 | Security / Privacy | 2 / 3 → **HIGH (6)** | ACCEPT (+ avoid public deployment) | 2 / 3 → HIGH (6), accepted | 2 / 3 → HIGH (6), accepted |
| R-03 | Technical / Quality | 2 / 2 → MEDIUM (4) | MITIGATE | 1 / 2 → LOW (2) | NOT VERIFIED |
| R-04 | People / Resources | 2 / 2 → MEDIUM (4) | MITIGATE | 1 / 2 → LOW (2) | NOT VERIFIED |
| R-05 | Scope / Requirements | 1 / 2 → LOW (2) | ACCEPT | 1 / 2 → LOW (2) | 1 / 2 → LOW (2), accepted |

**Open issues (not risks):** none known at `c08-status`. All three C06 findings are closed with
tests (see C06 above).

### R-01
- **Risk statement:** All tests and every recorded run use H2 (`MODE=PostgreSQL`). The
  `postgres` profile (`application-postgres.properties`) has never been run → on real PostgreSQL
  the schema, the dialect or the `SELECT … FOR UPDATE` court lock could behave differently from H2
  → the production configuration might not start, or the BR-02 guarantee would be unproven on the
  database it is meant for.
- **Category:** Release / Operation (also External dependency: the database).
- **Initial risk (L/I/priority/rationale):** L=2: H2's PostgreSQL mode is not PostgreSQL, and no
  run exists that shows they agree. I=3: BR-02 is the system's main invariant, and the lock is how
  it is enforced (ADR-6). → HIGH (6).
- **Response strategy and rationale:** MITIGATE. The decision not to require Docker in the build
  (C01, C03 Alternative B) stays. A one-off run against PostgreSQL is cheap and would remove the
  uncertainty.
- **Treatment, owner, due:** Start `postgres:16` (command in `application-postgres.properties`) and
  run the application with `-Dspring-boot.run.profiles=postgres`. Repeat the REST checks from the
  C06 assessment (create → confirm → overlapping confirm returns 409, approval path, cancel). Save
  the log as `docs/evidence/c08-postgres-run.log`. Planned owner Pavel Valošek, due 2026-11-03;
  **carried out by Rostislav Nevoral on 2026-11-01**.
- **Treatment evidence and verification status:** **done**,
  [`docs/evidence/c08-postgres-run.log`](evidence/c08-postgres-run.log), produced by
  [`docs/evidence/c08-postgres-checks.sh`](evidence/c08-postgres-checks.sh). Commit `1732e02`
  (`c08-report`, same code as `c08-status`), PostgreSQL 16.15 in Docker, profiles `postgres,seed`.
  The app started on PostgreSQL. Hibernate created the schema, and every C06 REST check gave the
  same result as on H2: create, confirm, overlapping confirm `409 CONFLICT`, adjacent interval
  available, approval path `DRAFT → PENDING_APPROVAL → CONFIRMED`, inactive court, cancel, and
  `start == end` → `400 INVALID_INTERVAL`. **BR-02 under concurrency on PostgreSQL:** 8 DRAFTs for
  the same slot were confirmed by 8 parallel HTTP requests. Result: 1 × `200`, 7 × `409`. The
  database holds exactly 1 `CONFIRMED` and 7 `DRAFT` rows.
- **Expected residual risk:** L=1, I=3 → MEDIUM (3). Concurrency on PostgreSQL is covered only by
  this manual run, not by `mvn test`.
- **Actual residual risk / NOT VERIFIED:** L=1, I=3 → **MEDIUM (3), verified** by the log above.
  I stays 3: a later schema or locking change could still differ between H2 and PostgreSQL, and
  `mvn test` would not show it.
- **Residual risk acceptance, owner, rationale:** MEDIUM accepted by Rostislav Nevoral on 2026-11-01.
  Automated PostgreSQL tests (Testcontainers) would reverse the C01 decision and are not needed
  for CP1.
- **Monitoring, trigger, contingency, next review:** Trigger: the run fails, or the schema or the
  locking code changes. Then re-run `docs/evidence/c08-postgres-checks.sh` against PostgreSQL. Review at C08
  (2026-11-03).

### R-02
- **Risk statement:** Identity is only asserted in the `X-Actor-Id` header (A-1; AD-3 out of
  scope; `ReservationController.java:52`). Any client can send a STAFF user's id → anyone can
  approve, reject or cancel reservations that are not theirs → the role rules (BR-08…BR-11) would
  have no protection in a real deployment.
- **Category:** Security / Privacy.
- **Initial risk (L/I/priority/rationale):** L=2: the header can be set from any HTTP client, but
  today the application runs only on developer machines. I=3: the approval rules can be bypassed
  completely. → HIGH (6).
- **Response strategy and rationale:** ACCEPT for the course scope. An identity provider is
  explicitly outside the agreed scope (AD-3), and C06 did not count it as a finding. To keep the
  scenario from occurring, the application is not deployed outside localhost.
- **Treatment, owner, due:** No change to the code. The limitation stays stated in the README and
  architecture docs and is said at the defence. Owner: Rostislav Nevoral; acceptance confirmed at
  the C08 defence on 2026-11-03.
- **Treatment evidence and verification status:** `docs/architecture-and-decisions.md` (identity
  provider: none), `docs/c03-architecture.md` (A-1). The acceptance is documented. There is no
  mitigation to verify.
- **Expected residual risk:** unchanged, L=2, I=3 → HIGH (6), accepted.
- **Actual residual risk / NOT VERIFIED:** HIGH (6), accepted. It is not lowered.
- **Residual risk acceptance, owner, rationale:** Accepted by the team (owner Rostislav Nevoral).
  Authentication is not a C01–C05 obligation, and the system has no real users.
- **Monitoring, trigger, contingency, next review:** Trigger: a plan to deploy beyond localhost, or
  CP2 requiring real users or roles. The risk then becomes a requirement (authentication in front
  of `ReservationController`). Next review: 2026-11-17 (CP2 planning).

### R-03
- **Risk statement:** ADR-6 serialises every allocation on one court through a
  `PESSIMISTIC_WRITE` lock. The lock wait under the C01 future pressure "10× members" has never
  been measured (C05 "Remaining design risk") → at peak, Confirm/Approve requests on a popular
  court queue or time out → users see slow or failed confirmations, and the C01 Q pressure has no
  evidence.
- **Category:** Technical / Quality.
- **Initial risk (L/I/priority/rationale):** L=2: the lock is per court and held only for one short
  transaction, but the wait has never been measured. I=2: correctness (BR-02) is not affected,
  only response time. → MEDIUM (4).
- **Response strategy and rationale:** MITIGATE: measure first. The lock scope is a deliberate
  design decision (ADR-6), so change it only if the measurement shows a real problem.
- **Treatment, owner, due:** A component test that runs N parallel confirms of non-overlapping
  slots on one court (e.g. N = 50) and records the maximum and p95 duration. Stated condition: H2,
  local machine. Owner: Pavel Mynář; due 2026-11-10.
- **Treatment evidence and verification status:** not done yet. No measurement exists.
- **Expected residual risk:** L=1, I=2 → LOW (2), if the measured wait is small relative to the
  request.
- **Actual residual risk / NOT VERIFIED:** NOT VERIFIED.
- **Residual risk acceptance, owner, rationale:** Pavel Mynář accepts LOW once the measurement is
  in `docs/evidence/`.
- **Monitoring, trigger, contingency, next review:** Trigger: the measured p95 is above 1 s, or a
  lock timeout appears in a test. Contingency: the lock stays (correctness first), and narrowing
  the lock becomes a new ADR. Review on 2026-11-10, when the measurement is due.

### R-04
- **Risk statement:** All three members can build and run the project. The detailed reasoning
  behind Court Allocation (C05 decision table, `CourtHold` transaction binding from C06-F1) was
  mostly written by one member → at the C08 defence or during a CP2 change, the other members
  cannot explain or safely modify the critical path → weaker defence (CP1 criterion "Engineering
  Review & Defence") and slower changes.
- **Category:** People / Resources.
- **Initial risk (L/I/priority/rationale):** L=2: the knowledge is concentrated, and the defence
  includes technical questions. I=2: it affects the grade and how fast changes can be made, not
  delivery as a whole. → MEDIUM (4).
- **Response strategy and rationale:** MITIGATE: spread the knowledge before the defence.
- **Treatment, owner, due:** A rehearsal of part A before C08. Each member presents one step
  (scope + BR-02; ADR-6; DD and C06-F1) and runs its test live. Owner: Rostislav Nevoral; due
  2026-11-03 12:00.
- **Treatment evidence and verification status:** not done yet. Evidence = the rehearsal took
  place, and each member ran their test live.
- **Expected residual risk:** L=1, I=2 → LOW (2).
- **Actual residual risk / NOT VERIFIED:** NOT VERIFIED.
- **Residual risk acceptance, owner, rationale:** The team accepts LOW after the rehearsal.
- **Monitoring, trigger, contingency, next review:** Trigger: a member is unavailable on
  2026-11-03, or cannot answer a question about their step at the rehearsal. Contingency: the
  remaining members cover the step. Review after the defence (2026-11-03).

### R-05
- **Risk statement:** The `Reason` type serves two purposes: it is OP-02's public response and the
  unit's binding verdict (C05 "Remaining design risk") → a future reason that only availability
  needs (e.g. "pending requests exist") would be added to `assess` → it would silently become a
  refusal of Confirm.
- **Category:** Scope / Requirements.
- **Initial risk (L/I/priority/rationale):** L=1: no such requirement is planned. I=2: Confirm
  would refuse wrongly. The exhaustive switch in `requireAdmissible` forces a decision on every new
  `Reason`, which limits the damage. → LOW (2).
- **Response strategy and rationale:** ACCEPT. Splitting the type now would be a speculative
  change.
- **Treatment, owner, due:** none. Owner: Rostislav Nevoral; next review 2026-11-17.
- **Treatment evidence and verification status:** the compile-time check is in place
  (`CourtAllocation.requireAdmissible`, a switch expression; C05-M2).
- **Expected residual risk:** LOW (2).
- **Actual residual risk / NOT VERIFIED:** LOW (2), accepted.
- **Residual risk acceptance, owner, rationale:** Rostislav Nevoral, for the reason above.
- **Monitoring, trigger, contingency, next review:** Trigger: the first new `Reason` constant or an
  availability-only requirement. Then split the advisory and binding types. Next review: 2026-11-17
  (CP2 planning).

## C08 — Controlled Change: test cases (CR-01)

Change Card (CR, impact, options, decision): [`intent-and-change.md` → C08 / CR-01](intent-and-change.md#c08--cr-01--controlled-change-card-cancellation-notice-period).

**Before baseline.** commit `b558fc1` (src/main identical to tag `c08-final` `07aacec` apart from one
removed comment) · BR-03 / REQ-05 @ `c02-baseline-v0.1.md` (+ v0.2 BR-03.1) · Java 21.0.4, Spring
Boot 3.5 · schema = Hibernate `ddl-auto` · isolated in-memory H2 (`MODE=PostgreSQL`, `create-drop`)
· `mvn test -Dtest=C08CancellationBaselineTest` · no secrets.

Both cases run through the real integration boundary (embedded HTTP server → controller →
`ReservationService` → JPA → H2) in `C08CancellationBaselineTest`. The oracle is the committed row
re-read with plain SQL (`JdbcTemplate`, `select … from reservation where id = ?`), not the HTTP
response. Each case runs twice on fresh data (step 5). Transcript:
[`evidence/c08-cr01-baseline-run.log`](evidence/c08-cr01-baseline-run.log).

### C08 / TC-C08-01 — member cancels a CONFIRMED booking well ahead (preserved behaviour)
- **Test basis:** BR-03.2 (`now < start`), BR-03.4 (cancel is not delete), REQ-05, BR-02. Risk: N/A.
- **Preconditions:** isolated DB; court R1 open 08:00–22:00; member M1; clock = 2026-09-20 09:00
  facility-local (Europe/Prague, `07:00Z`).
- **Test data:** B1 = M1, R1, `[2026-09-20 12:00, 13:00)` (half-open), created and confirmed over HTTP.

| # | Action / input | Expected BEFORE execution | Actual AFTER execution | Evidence |
| --- | --- | --- | --- | --- |
| 1 | Reset: new R1, M1; `POST /reservations` + `POST /{id}/confirm` | B1 `CONFIRMED` | `200 … state=CONFIRMED` (ids 3, 4) | log `[setup]` |
| 2 | SQL read of B1; availability `[12:00,13:00)` | `CONFIRMED`, `cancelled_at = null`; slot not available | `STATE=CONFIRMED, CANCELLED_AT=null`; available=false | log step 2 |
| 3 | `POST /reservations/{B1}/cancel` as M1 (3 h before start) | allowed: `200`, `CANCELLED` | `200 OK … state=CANCELLED` | log step 3 |
| 4 | SQL read of B1 after commit; availability | `CANCELLED`, `cancelled_at` set, row kept; slot available | `STATE=CANCELLED, CANCELLED_AT=2026-09-20T07:00Z`; available=true | log step 4 |
| 5 | Repeat 1–4 on fresh data | same outcome | run 2/2 identical | surefire `[2]` |

- **Final postcondition / oracle:** row exists with `CANCELLED`; slot free. **Verdict: PASS** (2/2).
- **Not covered:** concurrent cancel vs confirm (covered by `C03LifecycleConcurrencyTest`), PostgreSQL (H2 only here).

### C08 / TC-C08-02 — member cancels a CONFIRMED booking 1 h before start (behaviour CR-01 changes)
- **Test basis:** BR-03.2 of the before-baseline (`now < start` is the only time limit). Risk: N/A.
- **Preconditions:** as TC-C08-01.
- **Test data:** B1 = M1, R1, `[2026-09-20 10:00, 11:00)`; now = 09:00 → 1 h notice.

| # | Action / input | Expected BEFORE execution | Actual AFTER execution | Evidence |
| --- | --- | --- | --- | --- |
| 1 | Reset; create + confirm B1 over HTTP | B1 `CONFIRMED` | `200 … state=CONFIRMED` (ids 1, 2) | log `[setup]` |
| 2 | SQL read of B1 | `CONFIRMED`, `cancelled_at = null` | `STATE=CONFIRMED, CANCELLED_AT=null` | log step 2 |
| 3 | `POST /reservations/{B1}/cancel` as M1 | **allowed** under BR-03.2: `200`, `CANCELLED` | `200 OK … state=CANCELLED` | log step 3 |
| 4 | SQL read after commit; availability | `CANCELLED`; slot available | `STATE=CANCELLED`; available=true | log step 4 |
| 5 | Repeat on fresh data | same | run 2/2 identical | surefire `[2]` |

- **Verdict: PASS** against the before-baseline (2/2). This is the observed limitation CR-01 is about:
  the court is freed 1 h before start. **After CR-01 the oracle of this case changes** (AC-02:
  refused, stays CONFIRMED) — a recorded contract change, not a regression.
- **Not covered:** STAFF and DRAFT/PENDING variants (they become relevant only with CR-01; C09 tests).

**Execution summary:** 2 designed cases, 4 executions, 4 PASS, 0 FAIL / BLOCKED / NOT RUN
(surefire: `tests=4, failures=0, errors=0`), 2026-11-20, author R. Nevoral.

## C09 — Integration, CI and Release Record (CR-01)

**C08 Change Card and reviewed decision:** [`intent-and-change.md` → C08 / CR-01](intent-and-change.md#c08--cr-01--controlled-change-card-cancellation-notice-period)
— ACCEPT option A (notice-period guard in the existing owner). Peer review of the card: **still to
be filled in by the reviewer** (see the card).

**Before / after:** before = `b558fc1` (code) / `07f530e` (C08 card + baseline tests, tag `c08-cr01`)
→ after = **`399d5ef`** (tag `c09-rc1`). Schema identical before/after (no entity change);
config adds `reservation.cancellation.notice-period=PT2H`.

**Affected claims / new AC / unchanged regression basis:** BR-03.2 is refined by BR-03.6 for
MEMBER + CONFIRMED (AC-01..AC-05). Unchanged: BR-02, BR-03.1/3/4/5, REQ-06, approval workflow,
availability.

**Changed code / data / config / spec and justified N/A:**

| Item | Change |
| --- | --- |
| `ReservationService.cancel` | +1 guard (BR-03.6), notice period injected; negative value refused at start-up |
| `application.properties`, test `application.properties` | `reservation.cancellation.notice-period=PT2H` |
| `c02-baseline-v0.1.md` | BR-03.6 added, A-5 closed |
| Tests | new `C09CancellationNoticeTest` (10); TC-C08-02 oracle changed per CR; V-04.2 / V-04.8 fixture moved from 1 h to 3 h notice (oracle unchanged) |
| CI | `.github/workflows/ci.yml`, `scripts/verify.sh` |
| Schema / migration, API endpoints, ADRs | N/A — no new column, error code reused, ownership unchanged (ArchUnit rule passes) |

**Impact prediction check.** Before touching any test, the suite was run with only the code change:
exactly the 3 tests the C08 Impact Matrix predicted failed (V-04.2, V-04.8, TC-C08-02), the other 90
passed → [`evidence/c09-impact-prediction-run.log`](evidence/c09-impact-prediction-run.log).

**Peer review of the diff:** *TO FILL — reviewer, challenged assumption, disposition.* Points to
check: the guard runs after the BR-03.2 check (so `now ≥ start` keeps its old message); STAFF /
DRAFT / PENDING exemptions match the card; no write on refusal (exception → rollback).

| Test ID | Accepted oracle | Exact command / data | Expected | Actual | Verdict | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| TC-CR01-01 (AC-01) | member, CONFIRMED `[12:00,13:00)`, now 09:59:59 | `C09CancellationNoticeTest` over HTTP, SQL read | 200, row CANCELLED, slot free | 200, `STATE=CANCELLED` | PASS | H2 + PG logs |
| TC-CR01-02 (AC-03) | now 10:00:00 = start − 2 h | same | 400 `TOO_LATE_TO_CANCEL`, row CONFIRMED | 400, `STATE=CONFIRMED, CANCELLED_AT=null` | PASS | H2 + PG |
| TC-CR01-03 (AC-02) | now 11:00 | same | 400, CONFIRMED, slot blocked, message names PT2H / 10:00 | as expected | PASS | H2 + PG |
| TC-CR01-04..06 (AC-04) | STAFF / DRAFT / PENDING at 11:00 | same | 200, CANCELLED | 200, CANCELLED | PASS | H2 + PG |
| TC-CR01-07 (AC-04) | STAFF at now == start | same | 400, CONFIRMED | 400, CONFIRMED | PASS | H2 + PG |
| TC-CR01-08 (AC-04) | repeated cancel at 11:00 | same | 200, `cancelled_at` unchanged | as expected | PASS | H2 + PG |
| TC-CR01-09 (AC-04, BR-02) | refused late cancel, then overlapping confirm | same | 409 CONFLICT, exactly 1 CONFIRMED overlapping (SQL count) | 409, count = 1 | PASS | H2 + PG |
| TC-CR01-10 (AC-05) | `notice-period=PT0S`, now 11:00 | nested context | 200, CANCELLED (old behaviour) | 200, CANCELLED | PASS | H2 + PG |
| C08 regression TC-C08-01 | cancel 3 h ahead | `C08CancellationBaselineTest` ×2 | 200, CANCELLED, slot free | as expected | PASS | H2 + PG |
| C08 regression TC-C08-02 | cancel 1 h ahead — **oracle changed by CR-01** | same ×2 | 400, stays CONFIRMED | 400, `STATE=CONFIRMED` | PASS | H2 + PG |
| Full regression | all earlier suites (V-01..V-07, C03 race + ArchUnit, C05, CP1) | `mvn clean verify` | all pass | 104/104 | PASS | `c09-verify-run.log` |
| Demo profile | 28 scripted checks incl. V-04.x cancel | `mvn spring-boot:run -Dspring-boot.run.profiles=demo` | 28 / 0 failed | 28 checks, 0 failed | PASS | run on 2026-11-20 (console) |

**Real integration boundary and post-commit state check:** every C08/C09 case goes through the
embedded HTTP server, controller, service, JPA and the database, and re-reads the committed row
with plain SQL. Executed on H2 (in the full build) **and on PostgreSQL 16** (16 HTTP tests, 0
failures): [`evidence/c09-cr01-postgres-run.log`](evidence/c09-cr01-postgres-run.log).

**Dependency lock + runtime + DB schema + non-secret config:** Spring Boot parent `3.5.8` (all
starter versions managed by it), ArchUnit `1.4.1`; Java 21; schema = Hibernate `ddl-auto=update`
(postgres profile) from the entity mapping at `399d5ef`, no migration scripts; config as above.
There is no lockfile beyond the pinned parent POM — a known limitation.

**Clean checkout / build / test / run commands and environment:** see README *Build & run* and
*CI*. Executed in a fresh `git clone` at `399d5ef`, no local files: `mvn -B clean verify` → 104/104,
JAR started, smoke request answered →
[`evidence/c09-clean-checkout.log`](evidence/c09-clean-checkout.log). Windows 10, Java 21.0.4,
Maven 3.9 (IntelliJ bundled). **Performed by the author (R. Nevoral), not yet by another member.**

**CI job or automated local script, observed result / log:** `scripts/verify.sh --postgres` (same
steps as the CI workflow) at `399d5ef`: exit 0, `mvn clean verify` 104/104 + PostgreSQL 16/16 →
[`evidence/c09-verify-run.log`](evidence/c09-verify-run.log). GitHub Actions workflow
`.github/workflows/ci.yml` (build + tests + JAR hash; PostgreSQL 16 service job) is committed but
**NOT RUN yet** — it starts on the first push to GitHub.

**Independent reproducer:** **NOT RUN** — Pavel Valošek or Pavel Mynář: `git fetch --tags &&
git checkout c09-rc1 && mvn clean verify` in a new directory, then record date / OS / result here.

**Artifact identity:** `SWIReservationSystem-1.0-SNAPSHOT.jar` built from `399d5ef` by
`scripts/verify.sh`, sha256 `fe92e91752ae2c68fab5cdf028ae1dd56561818e01189ccc5122c6d4d1510ff3`
(this JAR was used for the C10 trials). The clean-checkout build of the same commit has sha256
`051add29…2635e7`: the build is **not bit-for-bit reproducible** (timestamps inside the JAR), so the
identity is commit + hash of the specific built file. No DB migration is required.

**Recovery / rollback limitation and remaining risks:** rollback = deploy the previous JAR, or set
`notice-period=PT0S` (config-only, AC-05 tested). CR-01 writes no new data (a refusal writes nothing),
so rolling back the code leaves no stale data. Remaining: peer review not recorded; independent
reproduction not done; GitHub CI not yet run; R-02 (asserted `X-Actor-Id`) still means anybody can
claim to be STAFF and bypass the notice period — accepted in C07/C08 R-02, CP2 requirement.

**Release candidate decision: CONDITIONAL** — owner Rostislav Nevoral, 2026-11-20. All new and
regression checks pass on H2 and PostgreSQL, and a clean checkout passes. Conditions for READY:
(1) peer review of the card and diff recorded, (2) independent clean checkout by another member,
(3) the GitHub Actions run on the pushed commit is green. Any FAIL in (1)–(3) → HOLD.

## C10 — Operational situations and logs

**Tested commits:** `399d5ef` (C09 RC JAR, sha256 `fe92e917…10ff3`) for situations 1–4; `8f64c9e`
(JAR sha256 `195d43db…196370`) for situation 3 repeated after the logging change. **Environment:**
Windows 10, Java 21.0.4, PostgreSQL 16.15 in the Docker container `swi-pg`, isolated database
`swi_ops` (created empty for this trial), app on port 8090 with profile `postgres` (+ `seed` on the
first start). Disposable test data only. HTTP and SQL transcript:
[`evidence/c10-operations-transcript.log`](evidence/c10-operations-transcript.log); application log
excerpts: [`evidence/c10-operations-log-excerpts.log`](evidence/c10-operations-log-excerpts.log).

| Situation | Expected and actual outcome | How correctness was verified | What do logs show / is a log needed? |
| --- | --- | --- | --- |
| **1. Successful confirmation** | *Expected:* create → 201 DRAFT, confirm → 200 CONFIRMED. *Actual:* 201 id 10, 200 CONFIRMED. **PASS** | SQL in a separate session: row 10 `CONFIRMED`, Kurt 2, `[2026-11-23 10:00, 11:00)`, owner 1. | Only the notification line `NOTIFY owner …: reservation 10 … CONFIRMED`. **No extra log needed** — the response plus the stored row prove the result. **Finding:** that line contained the member's **e-mail address** (personal data) → fixed in `8f64c9e`, it now logs `user#1`. |
| **2. Conflict rejected** | *Expected:* confirm of an overlapping booking → 409 `CONFLICT`, nothing changes. *Actual:* 409 `CONFLICT`. **PASS** | SQL: in the overlapping window exactly one `CONFIRMED` (id 10); the new booking 11 stays `DRAFT`. | **Nothing is logged, and that is the decision:** a refused overlap is a normal business outcome, the client gets a precise answer, there is no audit requirement, and logging every refusal would be noise. If an audit is required later, it belongs in a stored record, not in the technical log. |
| **3. Technical failure and recovery** | *Expected:* with the DB stopped an operation fails, writes nothing, and the app recovers after the DB is back. *Actual (before fix):* the request **hung 30 s**, then a generic **500**. After `docker start` the next request succeeded without restarting the app. **PASS** for data safety; **response and log were insufficient**. *Actual (after fix, `8f64c9e`):* **503 `TECHNICAL_FAILURE`** after 5 s, message "nothing was changed". **PASS** | Before any retry, SQL showed the failed create left **0 rows** (and the failed confirm in the repeat left id 14 `DRAFT`). Only then the operation was repeated → CONFIRMED. We did not retry blindly. | *Before:* Hikari / Hibernate lines gave the **time, the component and the failure type** (`SQLState 08001`, `Connection to localhost:5432 refused`, `CannotCreateTransactionException`), but **not which operation or actor** failed, and nothing about the outcome. *Added minimal record:* one ERROR line `TECHNICAL_FAILURE op=POST /reservations/14/confirm actor=1 type=CannotCreateTransactionException cause=ConnectException… outcome=NOT_STARTED (nothing written)`. No request body, no personal data. The DB **coming back is not logged**; it is visible only through the next successful request. |
| **4. Application restart** | *Expected:* after stop + start the confirmed bookings are still there and a new operation works. *Actual:* 10 and 12 still `CONFIRMED`, availability of `[10:00,11:00)` = `CONFLICT`, new booking 13 created + confirmed. **PASS** | SQL after restart (rows 10–12 unchanged), availability query, then SQL for row 13 `CONFIRMED`. | The **start** is fully visible (`Starting…`, active profile, `HikariPool-1 - Start completed`, `Started ReservationApplication in 4.79 s`). The **stop is not visible at all**: on Windows `taskkill` without `/F` could not stop the console process, `/F` kills it without a shutdown hook, so no shutdown line was written. Persistence does not depend on it (each operation commits). Limit: graceful shutdown was not tested. |

**Which events are worth logging?** Technical failures (DB / connection / transaction errors), with
time, operation, actor id, failure type and outcome. Start-up and configuration (already there).
Background jobs that change state (OP-07 already logs how many requests it expired). **Not**:
ordinary successes and business refusals such as a conflict or a too-late cancel — the response
and the stored state are the evidence, and the log would only fill with noise. Never personal data,
passwords or tokens.

**What did the logs actually tell us?** When the outage started and that it was a refused connection
to `localhost:5432` (time, component, type). After the C10 change also which request and actor were
affected and that nothing was written. That the application started, with which profile and pool.

**What had to be verified differently?** Every data state: that a refused or failed request wrote
nothing, that confirmed bookings survived the restart, that the conflict left only one CONFIRMED —
all by independent SQL in `psql`, because a log line cannot prove the database state. The
recovery after the outage (no log line) — by a successful request. The stop of the application —
not observable in the log at all (forced kill).

**Changes made because of C10** (commit `8f64c9e`, suite 107/107):
`ReservationController` handler for `DataAccessException` / `TransactionException` → 503 + one
ERROR line; `LoggingNotificationService` without e-mail; `spring.datasource.hikari.connection-timeout=5000`
in the postgres profile; `C10TechnicalFailureLoggingTest` (3 tests) pins the log line and the
response. **Not done / BLOCKED:** graceful shutdown on Windows (see situation 4); failure *during*
a commit (outcome UNKNOWN path) is covered by a unit test only, not by a real fault injection.
