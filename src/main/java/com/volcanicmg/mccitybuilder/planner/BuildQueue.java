package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Build all's queue of single requests to the server part: one in flight at a time, each kept until the server
 * answers. Accepted: done. "busy" (another paste or batch is running): tried again shortly, up to a limit. Anything
 * else, or no answer in time: kept, with the reason. Pure; the client sends and removes plan entries around it.
 *
 * @param <T> the queued item (a plan entry)
 */
public final class BuildQueue<T> {
    /** Wait before re-sending after "busy", tries per item, how long an answer may take (a big paste). */
    public static final long RETRY_MS = 1500, ANSWER_MS = 120_000;
    public static final int MAX_TRIES = 40;

    /** An item that stayed in the plan, why ("refused", "busy", "timeout") and the id it was last sent under. */
    public record Kept<T>(T item, String reason, int id) {
    }

    private final Deque<T> items = new ArrayDeque<>();
    private T waiting;
    private int waitingId, tries, total;
    private long sentAt, notBefore;
    private final List<T> done = new ArrayList<>();
    private final List<Kept<T>> kept = new ArrayList<>();

    public void add(T item) {
        items.add(item);
        total++;
    }

    /**
     * @param id the request id to use if an item is sent now
     * @return the item to send now (it is then in flight under {@code id}), or null
     */
    public T next(long now, int id) {
        if (waiting != null) {
            if (now - sentAt < ANSWER_MS) return null;
            kept.add(new Kept<>(waiting, "timeout", waitingId));
            waiting = null;
        }
        if (now < notBefore || items.isEmpty()) return null;
        waiting = items.poll();
        waitingId = id;
        sentAt = now;
        return waiting;
    }

    /**
     * @return the item the server finished (accepted), or null; a busy one goes back to the front. A late acceptance
     * of an item that timed out still counts (it was built; it must not be sent again).
     */
    public T answer(int id, boolean accepted, String reason, long now) {
        if (waiting == null || id != waitingId) {
            for (int i = 0; accepted && i < kept.size(); i++)
                if (kept.get(i).id() == id && kept.get(i).reason().equals("timeout")) {
                    T late = kept.remove(i).item();
                    done.add(late);
                    return late;
                }
            return null;
        }
        T it = waiting;
        waiting = null;
        if (accepted) {
            done.add(it);
            tries = 0;
            return it;
        }
        if ("busy".equals(reason) && ++tries < MAX_TRIES) {
            items.addFirst(it);
            notBefore = now + RETRY_MS;
            return null;
        }
        tries = 0;
        kept.add(new Kept<>(it, reason, id));
        return null;
    }

    /** @return true when the item is queued or in flight (a second Build must not send it again) */
    public boolean pending(T item) {
        return item.equals(waiting) || items.contains(item);
    }

    /** @return true when nothing is queued or in flight */
    public boolean idle() {
        return waiting == null && items.isEmpty();
    }

    /** @return items not answered yet (queued or in flight) */
    public int left() {
        return items.size() + (waiting != null ? 1 : 0);
    }

    public int total() {
        return total;
    }

    public List<T> done() {
        return done;
    }

    public List<Kept<T>> kept() {
        return kept;
    }

    /** Starts a new run's counts (call when idle, before adding a Build's items). */
    public void reset() {
        total = 0;
        done.clear();
        kept.clear();
    }
}
