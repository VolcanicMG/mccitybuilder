package com.volcanicmg.mccitybuilder.planner;

import java.util.List;

/**
 * The joint editor's plain-words fixing mode, pure: how an arm (a run meeting a joint piece) stands, which way its
 * on-screen Left / Right moves it, and the auto-fix pick over the search's ranked fits per joint turn.
 */
public final class JointHelp {
    /** An arm's state: it touches the joint piece, leaves a gap (no contact or a poor match), or would break blocks. */
    public static final int TOUCHING = 0, GAP = 1, BREAKS = 2;

    private JointHelp() {
    }

    /** @return {@link #TOUCHING}, {@link #GAP} or {@link #BREAKS} for an arm's fit */
    public static int armState(boolean destructive, int depth, double match) {
        return destructive ? BREAKS : depth < 0 || match < 0.3 ? GAP : TOUCHING;
    }

    /** @return the worst of the arms' states (the joint's) */
    public static int jointState(int[] arms) {
        int s = TOUCHING;
        for (int a : arms) s = Math.max(s, a);
        return s;
    }

    /**
     * @param out the arm's direction out of the joint (0 east, 1 south, 2 west, 3 north)
     * @return the shift step that moves the arm to the right as seen from its far end looking at the joint
     * (shifts run along +z for east / west arms, +x for north / south ones)
     */
    public static int rightShift(int out) {
        return out == 1 || out == 2 ? 1 : -1;
    }

    /**
     * Alignment keys carry no case (an L's left and right share their first arm's keys), so clearing one case's keys
     * must spare those another case of the same joint piece uses at its own turn.
     *
     * @param keys the keys one case would clear; @param usedElsewhere the keys the joint piece's other cases use now
     * @return the keys that may be cleared, in order
     */
    public static List<String> clearable(java.util.Collection<String> keys, java.util.Set<String> usedElsewhere) {
        List<String> out = new java.util.ArrayList<>();
        for (String k : keys) if (!usedElsewhere.contains(k)) out.add(k);
        return out;
    }

    /** @return the index of the first candidate (best first, all safe) that touches, or -1 */
    public static int firstTouching(List<PieceLayout.Fit> ranked) {
        for (int i = 0; i < ranked.size(); i++) if (armState(false, ranked.get(i).depth(), ranked.get(i).match()) == TOUCHING) return i;
        return -1;
    }

    /**
     * @param perTurn per joint turn (null: not laid out), per arm its ranked candidates ({@link PieceLayout#ranked})
     * @return {turn, then per arm the candidate index} for the turn where every arm touches with the most match in
     * all, or null when no turn has all arms touching (the pieces weren't made to join)
     */
    public static int[] autoFix(List<List<List<PieceLayout.Fit>>> perTurn) {
        int[] best = null;
        double bestScore = -1;
        for (int t = 0; t < perTurn.size(); t++) {
            List<List<PieceLayout.Fit>> arms = perTurn.get(t);
            if (arms == null) continue;
            int[] pick = new int[arms.size() + 1];
            pick[0] = t;
            double score = 0;
            boolean all = true;
            for (int a = 0; a < arms.size() && all; a++) {
                int i = firstTouching(arms.get(a));
                all = i >= 0;
                if (all) {
                    pick[a + 1] = i;
                    score += arms.get(a).get(i).match();
                }
            }
            if (all && score > bestScore + 1e-9) {
                best = pick;
                bestScore = score;
            }
        }
        return best;
    }
}
