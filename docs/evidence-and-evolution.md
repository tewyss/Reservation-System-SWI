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
