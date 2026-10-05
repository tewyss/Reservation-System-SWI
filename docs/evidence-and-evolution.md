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

## C03 — Architecture Evidence

Full work: [`c03-architecture.md`](c03-architecture.md) (sections A–L referenced below).

**Baseline:** v0.2 — [`c02-change-v0.2-approval.md`](c02-change-v0.2-approval.md), application at tag `baseline-v0.2` (`672c20d`).

**Part A:** the AS-IS mapping was produced from the code at `672c20d` (§A), since no Part A
document existed in the repository. Six findings. The decisive one is **F-A4**: approve + reject
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
- focused design class — §H2 (12 classes/interfaces, every H1 message owned)

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

**Commit/tag:** tag **`c03-architecture`** — the commit that contains this section, the
architecture document, the code changes and the logs above.
