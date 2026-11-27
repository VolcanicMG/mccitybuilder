package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.List;

/**
 * The joint check's verdicts as a count (the Pieces panel's "Fix joints (N)"): how many of a family's joint cases don't
 * fit, which ones, and the first to open. Pure.
 */
public final class JointCount {
    /** A case's state: it fits, it has a problem (overlap, no fit, off-centre), its pieces are still loading, or a piece failed to load for good (settled: not counted, not waited for). */
    public static final int OK = 0, BAD = 1, LOADING = 2, SETTLED = 3;

    private JointCount() {
    }

    /**
     * @param total   the cases the family has
     * @param bad     those with a problem
     * @param pending those still loading (or not tried yet)
     * @param failing the names of the bad cases, in case order
     * @param first   the index of the first bad case, or -1
     */
    public record Result(int total, int bad, int pending, List<String> failing, int first) {
        /** @return whether every case has a verdict */
        public boolean done() {
            return pending == 0;
        }

        /** @return the badge text: the number of bad cases ("99+" past 99), empty for none or while verdicts are pending */
        public String badge() {
            return !done() || bad == 0 ? "" : bad > 99 ? "99+" : String.valueOf(bad);
        }
    }

    /** @return the tally of {@code states} ({@link #OK} / {@link #BAD} / {@link #LOADING} per case, named by {@code labels}) */
    public static Result tally(List<String> labels, int[] states) {
        int bad = 0, pending = 0, first = -1;
        List<String> failing = new ArrayList<>();
        for (int i = 0; i < states.length; i++) {
            if (states[i] == BAD) {
                bad++;
                failing.add(labels.get(i));
                if (first < 0) first = i;
            } else if (states[i] == LOADING) pending++;
        }
        return new Result(states.length, bad, pending, failing, first);
    }
}
