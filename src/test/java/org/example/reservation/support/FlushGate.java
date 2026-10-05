package org.example.reservation.support;

import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.hibernate.CallbackException;
import org.hibernate.Interceptor;
import org.hibernate.type.Type;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes a lifecycle race deterministic instead of hoping a thread pool hits it.
 *
 * Registered as the Hibernate session-factory interceptor by the test that needs
 * it. When armed for a target state, the FIRST transaction that is about to write
 * a reservation into that state is held at its flush - i.e. after it has read and
 * decided, but before its UPDATE reaches the database and before it commits. The
 * test then runs a competing operation to completion and releases the gate.
 *
 * Purely test-side: it needs no hook in production code, so the same test runs
 * unchanged against the AS-IS and the TO-BE implementation.
 */
public class FlushGate implements Interceptor {

    private static volatile ReservationState gatedState;
    private static volatile CountDownLatch reached = new CountDownLatch(1);
    private static volatile CountDownLatch release = new CountDownLatch(1);
    private static final AtomicBoolean consumed = new AtomicBoolean();

    /** Hold the first flush that writes a reservation into {@code state}. */
    public static void arm(ReservationState state) {
        reached = new CountDownLatch(1);
        release = new CountDownLatch(1);
        consumed.set(false);
        gatedState = state;
    }

    /** Wait until the gated transaction is parked at its flush. */
    public static boolean awaitReached(long seconds) throws InterruptedException {
        return reached.await(seconds, TimeUnit.SECONDS);
    }

    /** Let the parked transaction continue to its UPDATE and commit. */
    public static void release() {
        release.countDown();
    }

    public static void disarm() {
        gatedState = null;
        release.countDown();
    }

    @Override
    public boolean onFlushDirty(Object entity, Object id, Object[] currentState, Object[] previousState,
                                String[] propertyNames, Type[] types) throws CallbackException {
        if (entity instanceof Reservation reservation
                && gatedState != null
                && reservation.getState() == gatedState
                && consumed.compareAndSet(false, true)) {
            reached.countDown();
            try {
                // Bounded, so a wrong interleaving fails the test instead of hanging the build.
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }
}
