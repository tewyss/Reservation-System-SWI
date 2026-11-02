# C08 – Clean build by every member (P1 / slide 03 WP3)

**Goal:** every team member checks out the reporting baseline and runs a clean build on their own machine.

**Command:**

```
git fetch --tags
git checkout c08-final        # 07aacec
mvn clean test
```

**Definition of Done (slide 05, P1):** `Tests run: 90, Failures: 0, Errors: 0, Skipped: 0` on each machine, recorded here.

| Member | Date | Baseline | Result | Evidence |
|---|---|---|---|---|
| Rostislav Nevoral | 2026-11-02 | `c08-final` (`07aacec`) | **90/90 pass** (0 failures, 0 errors, 0 skipped) | Local surefire reports from 2026-11-02 19:13: 8 suites, 90 test cases, none failed |
| Pavel Valošek | 2026-11-02 | `c08-final` (`07aacec`) | **90/90 pass** | Reported to R. Nevoral by message on 2026-11-02 (Tests run: 90, Failures: 0) |
| Pavel Mynář | 2026-11-02 | `c08-final` (`07aacec`) | **90/90 pass** | Reported to R. Nevoral by message on 2026-11-02 (Tests run: 90, Failures: 0) |

**Test suites (90 total):** BaselineV01VerificationTest 33, BaselineV02ApprovalVerificationTest 28,
CourtAllocationTest 16, C03ArchitectureRuleTest 4, C05CourtAllocationComponentTest 4,
C03LifecycleConcurrencyTest 2, CP1WalkingSkeletonTest 2, ReservationPersistenceSpikeTest 1.

**Environment needed:** JDK 21 + Maven. Tests use in-memory H2; Docker/PostgreSQL is not required.
