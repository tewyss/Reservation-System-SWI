# Task: C01 engineering spike

> This file is the team's record of the C01 change + review cycle (DoD item 6).
> It stands in for a tracker issue since the team works in one shared repository.

## Issue
**Title:** C01 engineering spike
**Goal:** Stand up the Sports Facility Reservation domain (Court / AppUser /
Reservation), the four operations with both business rules, the Notification
boundary, and prove persistence works via an executed Spike A (Reservation →
real DB → reload → verify).

## Change
Introduced the Spring Boot project skeleton, the domain model, `ReservationService`
(create / confirm / cancel / check availability) with the overlap rule and the
opening-hours rule, the `NotificationService` boundary, a minimal REST controller,
and tests — including `ReservationPersistenceSpikeTest` (the spike) and
`ReservationServiceTest` (business rules). Evidence: `docs/evidence-and-evolution.md`
and `docs/evidence/spike-A-persistence-run.log`.

- **Author:** Rostislav Nevoral

## Review (to be completed by another team member *before* integration)
Reviewer: Pavel Valošek 

Review checklist:
- [x] Domain model matches the Project Frame (Court, AppUser, Reservation, states).
- [x] Common overlap rule only counts CONFIRMED reservations; `[start, end)` is half-open.
- [x] Opening-hours (domain) rule enforced at confirm and in availability check.
- [x] Notification failure does not roll back a confirmation.
- [x] `mvn test` is green locally on the reviewer's machine (6/6).

Reviewer sign-off: _Pavel Mynář_
Review comments: _---_

## Integration
Integrated to `master`. (Team chose to commit on `master` in the shared repo;
the peer review above is completed before the team pushes / treats it as final.)
