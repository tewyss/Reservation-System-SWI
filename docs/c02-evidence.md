# C02 — Evidence

Minimal evidence linking the accepted specification to the running application.
Inputs to C03 and CP1.

---

## 1. Accepted baseline

| | |
| --- | --- |
| **Baseline v0.1** | [`c02-baseline-v0.1.md`](c02-baseline-v0.1.md) — accepted by team (§9.3). BR-01…BR-07, REQ-01…REQ-08, OP-01…OP-04, use-case view, lifecycle statechart, four activity flows, twelve consistency checks closed. |
| **Baseline v0.2** | [`c02-change-v0.2-approval.md`](c02-change-v0.2-approval.md) — accepted by team (§9). Adds BR-08…BR-11, REQ-09…REQ-15, OP-05/OP-06/OP-07, the updated use-case view and statechart, nine further consistency checks. |
| **Team** | Holy Trio — Pavel Valošek, Rostislav Nevoral, Pavel Mynář |
| **Order of work** | v0.1 specified → v0.1 accepted → app brought to v0.1 → **tagged** → change card impact-analysed → v0.2 accepted → app brought to v0.2 → tagged. The impact analysis was written against the frozen `baseline-v0.1` tag, before any v0.2 edit. |

---

## 2. Core operations demonstrated

All seven operations run in the application. Two independent demonstrations:

**a) Assert-backed verification suites** — `mvn test`, **62 tests, 0 failures**
([`evidence/c02-baseline-v0.2-verification.log`](evidence/c02-baseline-v0.2-verification.log)):

| Slice | Tests | Nested class in the log |
| --- | --- | --- |
| OP-01 Create | 6 | `OP-01 Create Reservation` |
| OP-02 Check Availability | 10 + 2 | `OP-02 Check Availability (fixture: CONFIRMED [10:00,11:00))`, `… approval disclosure (REQ-13)` |
| OP-03 Confirm | 9 + 4 | `OP-03 Confirm Reservation`, `OP-03 Confirm - the approval branch (REQ-09)` |
| OP-04 Cancel | 8 + 3 | `OP-04 Cancel Reservation`, `OP-04 Cancel - amended cancellable states (BR-03.1)` |
| OP-05 Approve | 9 | `OP-05 Approve Reservation` |
| OP-06 Reject | 5 | `OP-06 Reject Reservation` |
| OP-07 Expire | 5 | `OP-07 Expire Pending Approvals` |
| C01 persistence spike (regression) | 1 | `ReservationPersistenceSpikeTest` |

Each test is named with the **V-id of the specification example it executes**, so the
test report reads as a traceability matrix rather than a list of method names.

**b) A scripted run of the deployed application** —
`mvn spring-boot:run -Dspring-boot.run.profiles=demo`, **28 checks, 0 failed**
([`evidence/c02-baseline-v0.2-demo-run.log`](evidence/c02-baseline-v0.2-demo-run.log)).
It boots the real Spring Boot application against a real database and walks a
member, a second member and an approver through every operation, printing the
observed outcome of each check.

**c) HTTP surface** (`ReservationController`) — the same operations over REST:

```
POST /reservations                 X-Actor-Id   -> OP-01, 201 + {id, state}
GET  /reservations/availability                 -> OP-02, {available, reason, approvalRequired, pendingApprovalCount}
POST /reservations/{id}/confirm     X-Actor-Id  -> OP-03
POST /reservations/{id}/cancel      X-Actor-Id  -> OP-04
POST /reservations/{id}/approve     X-Actor-Id  -> OP-05
POST /reservations/{id}/reject      X-Actor-Id  -> OP-06
POST /reservations/expire-due                   -> OP-07 (also on a timer, ApprovalExpirySweeper)
```

---

## 3. Verification examples actually executed

Every slice has **at least one success and at least one relevant
negative/boundary** example executed.

| Slice | Success executed | Negative / boundary executed |
| --- | --- | --- |
| **OP-01** Create | V-01.1, V-01.2 | V-01.3 (`start == end`), V-01.4, V-01.5, V-01.6 |
| **OP-02** Availability | V-02.1, V-02.3 (interval boundaries), V-02.8, V-02.9, V-02.11 | V-02.2, V-02.4, V-02.5, V-02.6, V-02.7, V-02.10 |
| **OP-03** Confirm | V-03.1, V-03.3 (boundary touch), V-03.10, V-03.11 | V-03.2, V-03.4, V-03.5, V-03.6, V-03.7, V-03.8, V-03.12 |
| **OP-03** concurrency | — | **V-03.9** — 8 threads, exactly 1 CONFIRMED |
| **OP-04** Cancel | V-04.1, V-04.2, V-04.3, V-04.7, V-04.9 | **V-04.4 (`now == start`)**, V-04.5, V-04.6, V-04.8, V-04.10, D7-EXPIRED |
| **OP-05** Approve | V-05.1, V-05.3 (second approver succeeds) | V-05.2, **V-05.3 (`SELF_APPROVAL`)**, **V-05.4 (`now == deadline`)**, V-05.5, V-05.6, V-05.7, V-05.8 |
| **OP-05** concurrency | — | **V-05.9** — 2 threads, exactly 1 CONFIRMED |
| **OP-06** Reject | V-06.1, F5 (refusal after the deadline) | V-06.2, V-06.3, V-06.4 |
| **OP-07** Expire | V-07.1, **V-07.3 (`now == deadline`)** | **V-07.2 (`deadline − 1 min`)**, V-07.4 (no-op), G3 (cancel beats the sweep) |
| **BR-10** | deadline clamped to reservation start | — |

Both sides of every interval/time boundary in the specification are executed, not
just the comfortable side:

| Boundary | Below | On | Above |
| --- | --- | --- | --- |
| BR-01 overlap, `[start,end)` | V-02.1 `[09:00,10:00)` AVAILABLE | V-02.4 identical → CONFLICT | V-02.3 `[11:00,12:00)` AVAILABLE |
| BR-01 validity | — | V-01.3 / V-02.10 `start == end` rejected | V-01.1 valid |
| BR-04 opening hours | V-02.6 before opening | `end == closingTime` allowed (V-03.3 fixture) | V-02.7 / V-03.4 past closing |
| BR-03.2 cancel, `now < start` | V-04.1 now = 09:00 | **V-04.4 now = 10:00 → rejected** | V-04.5 now = 10:30 → rejected |
| BR-10 expiry, `now >= deadline` | V-07.2 now = 09:59 → pending | **V-07.3 / V-05.4 now = 10:00 → EXPIRED** | V-07.1 now = 10:30 → EXPIRED |

### The concurrency requirement was verified to have teeth

REQ-04 / REQ-15 are the requirements most easily "passed" by a test that never
actually races. A **control run** was therefore executed with the per-court
serialisation removed and nothing else changed
([`evidence/c02-req04-control-run-without-serialisation.log`](evidence/c02-req04-control-run-without-serialisation.log)):

```
[ERROR] BaselineV01VerificationTest.v03_9
expected: 1L
 but was: 3L
```

**Three** reservations reached CONFIRMED for the same court and the same slot — a
real BR-02 violation, and exactly the double-booking named in C01 as the selected
future pressure (Q / Scale). With the serialisation in place the same example
yields 1. The requirement is enforced, and the test can tell the difference.

---

## 4. Mismatches found and resolved

Four mismatches between the specification and the implementation were found by
executing the examples. In each case the team decided **where** the defect was
before changing anything.

### M-1 — Availability disagreed with Confirm *(defect in the specification, then the implementation)*
- **Found by:** consistency check C-3, then confirmed against the C01 code.
- **Symptom:** the C01 `isAvailable` mixed the overlap rule and the opening-hours
  rule into one boolean with no reason, while the exercise's REQ-02 describes only
  overlap. So a slot outside opening hours could be reported one way and confirmed
  another, and "unavailable" carried no actionable information.
- **Located in:** *the specification first*. REQ-02 as given was under-specified;
  widening it to the same guard set Confirm enforces (BR-04, BR-07) plus a reason
  code is what makes the two views consistent.
- **Resolved:** REQ-02 widened (v0.1 §9.1 C-3); `AvailabilityResult` now carries a
  `Reason`; examples V-02.6, V-02.7 and V-02.10 added and executed.

### M-2 — The specified lazy expiry was silently discarded *(defect in the implementation)*
- **Found by:** **V-05.4 failing.** `expected: EXPIRED but was: PENDING_APPROVAL`.
- **Symptom:** OP-05 outcome E7 requires a failure that *changes state* — the
  approval is rejected with `APPROVAL_EXPIRED` **and** the request becomes
  `EXPIRED`. The implementation wrote the new state and then threw; Spring rolled
  the transaction back and the write vanished. The error code was right, the state
  change was lost.
- **Located in:** the **implementation**. The specification is coherent (and E7 is
  deliberately a state-changing failure, so that correctness does not depend on the
  sweep having run). Throwing inside the same transaction simply cannot deliver it.
- **Resolved:** the expiry is committed in its own transaction
  (`LazyApprovalExpiry`, `REQUIRES_NEW`). V-05.4 now passes and asserts both the
  error code and the persisted `EXPIRED` state.
- **Consequence:** recorded as **architecture driver AD-8** — "which failures
  commit and which roll back" is now an implicit per-method convention, and C03
  must make it an explicit design decision.

### M-3 — A verification example was not isolated *(defect in the verification example)*
- **Found by:** **V-07.3 failing.** `expected: 1 but was: 2`.
- **Symptom:** OP-07 sweeps the **whole system** by design, so pending requests
  left behind by sibling examples were swept too and the count was higher than the
  example expected.
- **Located in:** the **verification example**, not the specification or the
  implementation. A global sweep counting everything due is correct behaviour;
  asserting an exact count while sharing a database with other examples is not a
  valid observation.
- **Resolved:** each example now starts from a known reservation set, so the count
  is a meaningful observation of REQ-12.

### M-4 — The demo script asserted the wrong owner *(defect in the demonstration)*
- **Found by:** the demo run reporting `[FAIL] V-04.6 … -> UNAUTHORIZED`.
- **Symptom:** the script had Bob cancel a reservation **Bob owned**, which BR-05
  permits, so no rejection occurred.
- **Located in:** the **demonstration script**. The rule, the implementation and
  the JUnit V-04.6 were all correct.
- **Resolved:** the script now has Bob attempt Alice's reservation. Worth recording
  because it is the failure mode the exercise warns about — an example that appears
  to verify a rule while actually exercising a different case.

---

## 5. Change impact summary

| | |
| --- | --- |
| **Changed condition** | Some courts require approval by an authorized person before a reservation may become CONFIRMED; approval may be delayed, rejected or expire. |
| **Affected** | OP-03 (target state becomes conditional), REQ-03, OP-04/REQ-05/BR-03.1 (PENDING_APPROVAL becomes cancellable), OP-02/REQ-02 (extended with disclosure), REQ-04 → REQ-15, the statechart, the use-case view, and the Notification interface. |
| **Unaffected, with reason** | **OP-01/REQ-01** — Create was already a claim, not an allocation (v0.1 finding C-1), and the gate sits on the transition into CONFIRMED. **BR-01** — the change says nothing about intervals. **BR-02** — deliberately unchanged: the blocking set is still exactly `{CONFIRMED}` (decision D-2). **BR-04**, **BR-06**, **REQ-06**, **REQ-07/BR-05**, and the notification failure semantics. Full table: change doc §2.2. |
| **New actor / operations** | **Approver** (STAFF, not the owner) → OP-05 Approve, OP-06 Reject. **Clock** (non-human, time trigger) → OP-07 Expire. |
| **Changed rules / state semantics** | BR-03.1 amended; new BR-08 (per-court approval), BR-09 (authority + separation of duty), BR-10 (deadline `min(now+window, start)`, expiry at `now >= deadline`), BR-11 (terminality). New states `PENDING_APPROVAL`, `REJECTED`, `EXPIRED` — and `PENDING_APPROVAL` deliberately does **not** block. |
| **Views updated** | Use-case view (change doc §7a) and lifecycle statechart (§7b), each with an edge-by-edge traceability table to the slices and rules; four new activity flows (§7c). |
| **Regression evidence that "unaffected" is true** | Every v0.1 verification example is still executed unchanged and still gives the same answer — 34 of the 62 tests. Had BR-01 or BR-02 quietly shifted, V-02.1/V-02.3/V-03.3/V-03.9 would have changed answer. |
| **Rejected alternatives** | Pending requests holding the slot; auto-approval on timeout; resubmission of a rejected request; per-court approver assignment. Reasons in change doc §2.3. |

---

## 6. Remaining assumptions / unknowns

Carried into C03 rather than invented away.

| id | Kind | Short form |
| --- | --- | --- |
| **A-1** | assumption | Actor identity is asserted by the caller, not authenticated → **AD-3**. |
| **A-2** | assumption | An availability answer is advisory and may be stale; the guarantee is at confirm/approve time. |
| **A-3** | assumption | v0.1 places no lower time bound on Confirm — a past slot may be confirmed. Deliberately asymmetric with Cancel. |
| **A-4** | assumption | Changing a court's opening hours does not invalidate existing CONFIRMED reservations. |
| **A-5** | **unknown** | Whether a cancellation notice period / no-show penalty is wanted. Isolated to BR-03.2. |
| **A-6** | **unknown** | Whether a member may hold several concurrent reservations, or a quota applies (from C01). |
| **A-7** | **unknown** | The approval window has no stakeholder-supplied value; it is configuration (default 24 h), not a business constant. The justified part of BR-10 (`deadline <= start`) is independent of it. |
| **A-8** | assumption | Members accept that a pending request does not hold the slot. Changing this would be a change to **BR-02** — a major change, not a tweak. |
| **A-9** | **unknown** | Whether a REJECTED request may be resubmitted or appealed. |
| **A-10** | assumption | Any STAFF user other than the owner may decide any request; no per-court approver assignment. |
| **A-11** | assumption | Toggling `requiresApproval` does not reclassify already-submitted requests. |
| **A-12** | assumption | The sweep's frequency is operational, not contractual — lazy expiry (G2) preserves correctness. |

---

## 7. Architecture drivers carried into C03

| id | Driver | Evidence it is real |
| --- | --- | --- |
| **AD-1** | The BR-02 invariant must survive concurrent allocation. | The control run double-books the court (3 CONFIRMED) without serialisation. C01's selected future pressure, now demonstrated rather than predicted. |
| **AD-2** | Time must be injectable, not ambient. | **Already paid off:** V-04.4 and V-07.3 place "now" exactly on a boundary, and the whole expiry feature needed no time-handling rework. |
| **AD-3** | Actor identity must be established, not asserted. | A-1; `X-Actor-Id` is trusted today, and BR-05/BR-09 are only as strong as it is. |
| **AD-4** | The lifecycle is a real state machine and needs an explicit home. | 6 states / 10 guarded transitions encoded as `if`-cascades in one service class; the guard set is duplicated between `confirm` and `approve`. |
| **AD-5** | A time-triggered process has entered a request-driven system. | OP-07 has no human actor and no request. `ApprovalExpirySweeper` + `@EnableScheduling` are the first non-request entry point; multi-instance behaviour is undecided. |
| **AD-6** | The allocation guarantee's enforcement point has multiplied. | Two operations now transition into CONFIRMED, and both must remember to call the same guard. REQ-15 must hold across both (V-05.9). |
| **AD-7** | The availability read model is diverging from the write model. | REQ-13 makes the highest-traffic operation combine the blocking set, a pending count and a court property. |
| **AD-8** | Failure outcomes that must persist state have no home in the layering. | **Found by a failing test (M-2)**, not by prediction. Solved with a `REQUIRES_NEW` collaborator, which leaves the transaction boundary an implicit convention. |

**The single most concrete driver for C03:** *AD-1 + AD-6* — the exclusive-resource
invariant is currently guaranteed by a pessimistic lock taken inside two separate
service methods, and the control run proves that removing it double-books. C03 must
decide where that guarantee lives (a database exclusion/unique constraint, one
funnel every allocation passes through, or serializable isolation) so that it holds
however many operations can allocate, and so that no future operation can forget it.

---

## 8. Application commit / tag

| Tag | Contents | Tests |
| --- | --- | --- |
| **`baseline-v0.1`** | Accepted Specification Baseline v0.1 + the application matching it. | 34 / 34 green |
| **`baseline-v0.2`** | The approval change: impact analysis, Baseline v0.2, and the application matching it. | 62 / 62 green |

Reproduce:

```bash
mvn clean test                                        # 62 tests, 0 failures
mvn spring-boot:run -Dspring-boot.run.profiles=demo   # 28 checks, 0 failed
```

### Committed run logs

| File | What it shows |
| --- | --- |
| [`evidence/c02-baseline-v0.1-verification.log`](evidence/c02-baseline-v0.1-verification.log) | v0.1: 34/34 green. |
| [`evidence/c02-baseline-v0.2-verification.log`](evidence/c02-baseline-v0.2-verification.log) | v0.2: 62/62 green, including every v0.1 example as regression. |
| [`evidence/c02-baseline-v0.2-demo-run.log`](evidence/c02-baseline-v0.2-demo-run.log) | The running application walked through all seven operations: 28 checks, 0 failed. |
| [`evidence/c02-req04-control-run-without-serialisation.log`](evidence/c02-req04-control-run-without-serialisation.log) | The control run: without serialisation, 3 reservations double-book one slot. |
| [`evidence/spike-A-persistence-run.log`](evidence/spike-A-persistence-run.log) | C01 persistence spike (unchanged, still green as a regression). |

---

## 9. Definition of Done

| # | Item | Where |
| --- | --- | --- |
| 1 | All four core operations have complete textual slices | v0.1 §3–§6 |
| 2 | Every accepted requirement passed the acceptance gate | v0.1 §7 (REQ-01…08), v0.2 §10 (REQ-09…15) |
| 3 | Shared business rules defined once | v0.1 §1 (BR-01…07), v0.2 §3 (BR-08…11) |
| 4 | One use-case / actor-goal view covers the minimum system | v0.1 §8a |
| 5 | One lifecycle statechart covers the minimum system | v0.1 §8b |
| 6 | Slices, requirements, views mutually consistent | v0.1 §9 (C-1…C-12), v0.2 §7e (C-13…C-21) |
| 7 | Baseline v0.1 explicitly accepted by the team | v0.1 §9.3 |
| 8 | The app demonstrates all four core operations | §2 above; 62 tests + 28 demo checks |
| 9 | One success + one negative/boundary example executed per slice | §3 above |
| 10 | Change impact analysed **before** editing | v0.2 §2, written against the frozen `baseline-v0.1` tag |
| 11 | Affected and unaffected parts explicit | v0.2 §2.1 / §2.2, with a reason per unaffected item |
| 12 | Any new Approve slice fully specified | v0.2 §6 — OP-05, plus OP-06 and OP-07 in the same structure |
| 13 | Use-case view and statechart updated for v0.2 | v0.2 §7a / §7b, with edge-to-rule traceability |
| 14 | Running app matches accepted Baseline v0.2 | §2, §8 |
| 15 | Evidence links specification to running behaviour | Every test is named with the V-id it executes |
| 16 | At least one concrete architecture driver ready for C03 | §7 — eight, with AD-1 + AD-6 as the primary one |
