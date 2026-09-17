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
midnight. *(Enforced in `ReservationService.confirm` and `isAvailable`.)*

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
