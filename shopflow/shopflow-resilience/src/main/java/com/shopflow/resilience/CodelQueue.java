package com.shopflow.resilience;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * CoDel (Controlled Delay) active-queue management, adapted from packets to requests (Nichols & Jacobson,
 * RFC 8289). It decides <i>which queued work to shed</i> under overload — the queue-discipline complement to
 * {@link AimdConcurrencyLimiter} (which decides the concurrency <i>limit</i>) and {@link Bulkhead} (which
 * caps concurrency outright).
 *
 * <p><b>The bufferbloat insight.</b> The obvious overload signal is queue <i>length</i> — shed when the
 * queue is "too long". But length lies: a deep queue that is draining fast is fine, and a short queue that
 * is barely moving is a disaster. What actually hurts the user is <b>sojourn time</b> — how long their
 * request sat waiting — so CoDel measures that directly. The key refinement is distinguishing a <i>good</i>
 * queue (a transient burst that drains within a round-trip) from a <i>standing</i> queue (one that stays
 * backed up). CoDel only sheds once the sojourn time has stayed at or above {@code target} continuously for
 * a full {@code interval} — a brief spike is absorbed, a persistent backlog is attacked.
 *
 * <pre>
 *   good queue:  sojourn spikes above target, falls back within interval  -> absorb, shed nothing
 *   standing:    sojourn stays >= target for a whole interval             -> start shedding
 * </pre>
 *
 * <p><b>The control law.</b> Once shedding, successive drops are scheduled at shrinking gaps so the
 * controller pushes the delay back under {@code target} with gentle but accelerating pressure:
 *
 * <pre>
 *   dropNext = t + interval / sqrt(count)
 * </pre>
 *
 * where {@code count} is how many drops this overload episode has caused — so the more it has to drop, the
 * faster the next drop comes, and it backs off the instant sojourn recovers. (On re-entering a drop state
 * soon after leaving one, {@code count} resumes near where it left off rather than from 1, so a flapping
 * overload doesn't keep paying the slow initial ramp — the Linux/RFC heuristic.)
 *
 * <p>This is request-level AQM: enqueue work with {@link #offer}, and when a worker is ready call
 * {@link #poll} — it returns the item to serve and the list of items CoDel decided to shed (the caller fails
 * those fast, e.g. HTTP 503, rather than letting them wait behind a standing queue and time out anyway).
 * Time is passed in (nanos) so tests are deterministic; pass {@code System.nanoTime()} in production.
 *
 * <p>Not thread-safe; guard externally, like a plain queue.
 */
public final class CodelQueue<T> {

    private static final class Stamped<T> {
        final T value;
        final long enqueuedNanos;
        Stamped(T value, long enqueuedNanos) {
            this.value = value;
            this.enqueuedNanos = enqueuedNanos;
        }
    }

    /** Result of a {@link #poll}: the item to serve (or null if none) and any items shed in the process. */
    public static final class Served<T> {
        private final T item;
        private final List<T> dropped;
        Served(T item, List<T> dropped) {
            this.item = item;
            this.dropped = dropped;
        }
        /** The item to serve now, or {@code null} if the queue drained. */
        public T item() {
            return item;
        }
        /** Items CoDel shed during this poll — fail these fast. Never null; usually empty. */
        public List<T> dropped() {
            return dropped;
        }
    }

    private final long targetNanos;
    private final long intervalNanos;
    private final int minBacklog;   // don't shed when this many or fewer items remain after dequeue (RFC's MTU floor)

    private final Deque<Stamped<T>> queue = new ArrayDeque<>();

    // CoDel controller state (RFC 8289 §5.2); UNSET distinguishes "no time set" from a real 0 timestamp.
    private static final long UNSET = Long.MIN_VALUE;
    private long firstAboveTime = UNSET;
    private long dropNext = UNSET;
    private int count;
    private int lastCount;
    private boolean dropping;

    /**
     * @param targetNanos    acceptable standing-queue sojourn (RFC default 5ms); shed to keep delay under this
     * @param intervalNanos  how long sojourn must stay above target before shedding starts (RFC default 100ms)
     * @param minBacklog     never shed when at most this many items remain after a dequeue (keeps a small queue)
     */
    public CodelQueue(long targetNanos, long intervalNanos, int minBacklog) {
        if (targetNanos <= 0 || intervalNanos <= 0) {
            throw new IllegalArgumentException("target and interval must be positive");
        }
        if (minBacklog < 0) {
            throw new IllegalArgumentException("minBacklog must be >= 0");
        }
        this.targetNanos = targetNanos;
        this.intervalNanos = intervalNanos;
        this.minBacklog = minBacklog;
    }

    /** CoDel's RFC defaults: 5ms target, 100ms interval. */
    public static <T> CodelQueue<T> defaults() {
        return new CodelQueue<>(5_000_000L, 100_000_000L, 0);
    }

    public void offer(T value, long nowNanos) {
        queue.addLast(new Stamped<>(value, nowNanos));
    }

    public int size() {
        return queue.size();
    }

    /** Dequeue the next item to serve, shedding items per CoDel while the queue is a standing one. */
    public Served<T> poll(long nowNanos) {
        List<T> dropped = new ArrayList<>();
        Deq<T> r = dodequeue(nowNanos);
        if (r.item == null) {               // queue empty
            dropping = false;
            return new Served<>(null, dropped);
        }
        if (dropping) {
            if (!r.okToDrop) {
                dropping = false;           // sojourn recovered -> stop shedding
            } else {
                while (dropping && nowNanos >= dropNext) {
                    dropped.add(r.item);     // a large backlog can need several drops "now"
                    count++;
                    r = dodequeue(nowNanos);
                    if (r.item == null) {
                        dropping = false;
                        break;
                    }
                    if (!r.okToDrop) {
                        dropping = false;
                    } else {
                        dropNext = controlLaw(dropNext, count);
                    }
                }
            }
        } else if (r.okToDrop) {            // enter the drop state
            dropped.add(r.item);
            r = dodequeue(nowNanos);
            dropping = true;
            int delta = count - lastCount;
            count = 1;
            if (delta > 1 && (nowNanos - dropNext) < 16L * intervalNanos) {
                count = delta;              // resume a recent episode instead of ramping from 1
            }
            dropNext = controlLaw(nowNanos, count);
            lastCount = count;
        }
        return new Served<>(r.item, dropped);
    }

    // --- internals (RFC 8289 §5.6) -----------------------------------------

    private static final class Deq<T> {
        final T item;
        final boolean okToDrop;
        Deq(T item, boolean okToDrop) {
            this.item = item;
            this.okToDrop = okToDrop;
        }
    }

    private Deq<T> dodequeue(long now) {
        Stamped<T> head = queue.pollFirst();
        if (head == null) {
            firstAboveTime = UNSET;
            return new Deq<>(null, false);
        }
        long sojourn = now - head.enqueuedNanos;
        boolean okToDrop = false;
        if (sojourn < targetNanos || queue.size() <= minBacklog) {
            firstAboveTime = UNSET;         // below target, or backlog drained to the floor
        } else if (firstAboveTime == UNSET) {
            firstAboveTime = now + intervalNanos;   // just went above: shed if still above at this time
        } else if (now >= firstAboveTime) {
            okToDrop = true;                // above target continuously for a full interval
        }
        return new Deq<>(head.value, okToDrop);
    }

    private long controlLaw(long t, int cnt) {
        return t + (long) (intervalNanos / Math.sqrt(cnt));
    }
}
