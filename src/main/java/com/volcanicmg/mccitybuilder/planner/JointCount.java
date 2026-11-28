package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.List;

/**
 * The joint check's verdicts as a count (the Pieces panel's "Fix joints (N)"): how many of a family's joint cases have a
 * real problem (broken or overlapping blocks, a hole through a wall or a break in a road's surface), how many only a minor one (they fit and touch: a gap in trim, a berm or a kerb, off-centre),
 * which ones, and the one to open first. Pure.
 */
public final class JointCount {
    /**
     * A case's state: it fits, it has a real problem (overlap, no fit), its pieces are still loading, a piece failed to
     * load for good (settled: not counted, not waited for), or it only has a minor one (off-centre).
     */
    public static final int OK = 0, BAD = 1, LOADING = 2, SETTLED = 3, MINOR = 4;

    private JointCount() {
    }

    /**
     * @param total      the cases the family has
     * @param bad        those with a real problem
     * @param minor      those with only a minor one
     * @param pending    those still loading (or not tried yet)
     * @param failing    the names of the real ones, in case order
     * @param minorNames the names of the minor ones
     * @param first      the index of the first real case, or -1
     * @param firstMinor the index of the first minor case, or -1
     */
    public record Result(int total, int bad, int minor, int pending, List<String> failing, List<String> minorNames, int first, int firstMinor) {
        /** @return whether every case has a verdict */
        public boolean done() {
            return pending == 0;
        }

        /** @return the badge text: the number of real problems ("99+" past 99), empty for none or while verdicts are pending (minor ones never badge) */
        public String badge() {
            return !done() || bad == 0 ? "" : bad > 99 ? "99+" : String.valueOf(bad);
        }

        /** @return the case a click opens: the first real one, else the first minor one, else -1 */
        public int openIndex() {
            return first >= 0 ? first : firstMinor;
        }
    }

    /**
     * @return the state for a case's issues ({@code overlap}, {@code opening}, {@code towergap}, {@code nofit}, {@code offcentre}): none = OK; a
     * real problem (blocks broken, or a hole through the wall / a break in the road surface) = BAD; anything else (a gap
     * in trim, berm, kerb or parapet only, a tower's slit beside a wall end, a partial mismatch, off-centre) = MINOR
     */
    public static int stateOf(List<String> issues) {
        if (issues.isEmpty()) return OK;
        return issues.contains("overlap") || issues.contains("opening") ? BAD : MINOR;
    }

    /** @return the tally of {@code states} ({@link #OK} / {@link #BAD} / ... per case, named by {@code labels}) */
    public static Result tally(List<String> labels, int[] states) {
        int bad = 0, minor = 0, pending = 0, first = -1, firstMinor = -1;
        List<String> failing = new ArrayList<>(), minorNames = new ArrayList<>();
        for (int i = 0; i < states.length; i++) {
            if (states[i] == BAD) {
                bad++;
                failing.add(labels.get(i));
                if (first < 0) first = i;
            } else if (states[i] == MINOR) {
                minor++;
                minorNames.add(labels.get(i));
                if (firstMinor < 0) firstMinor = i;
            } else if (states[i] == LOADING) pending++;
        }
        return new Result(states.length, bad, minor, pending, failing, minorNames, first, firstMinor);
    }
}
