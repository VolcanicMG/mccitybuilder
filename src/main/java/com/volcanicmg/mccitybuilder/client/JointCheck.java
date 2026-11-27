package com.volcanicmg.mccitybuilder.client;

import com.volcanicmg.mccitybuilder.planner.JointCount;
import java.util.List;

/**
 * How many of the line panel's family's joint cases don't fit (the Pieces panel's "Fix joints (N)"), cached per pack and
 * family. Worked out one case per frame ({@link JointEditorScreen.Probe}), so no frame stalls; worked out again when the
 * set-up, an alignment, the level or the loaded pieces change, and a second later while pieces were still loading. The
 * last finished tally of the same family stays shown (and clickable) while a new one is worked out.
 */
final class JointCheck {
    private static List<Object> stamp = List.of();
    private static JointEditorScreen.Probe probe;
    // The in-progress tally; the last finished one with the probe that made it and the family it is for.
    private static JointCount.Result working, shown;
    private static JointEditorScreen.Probe shownProbe;
    private static String shownFor = "";
    private static long retryAt;

    private JointCheck() {
    }

    /**
     * Advances the family's check by one case and returns what is known; call once per frame while the count shows.
     *
     * @param fam   the family key as the editor takes it ({@code tool:key})
     * @param level the level the panel shows (the selected line's, else the line tool's)
     * @return the last finished tally of this family (or the one in progress when there is none yet), or null while its pieces are loading
     */
    static JointCount.Result of(String pack, String fam, int level) {
        List<Object> now = List.of(pack, fam, PieceCatalog.setupVersion, PieceCatalog.loadVersion, PieceCatalog.overrides(pack).hashCode(), level);
        if (!now.equals(stamp)) {
            stamp = now;
            probe = new JointEditorScreen.Probe(pack, fam, level);
            working = null;
            retryAt = 0;
            // Another family's tally is stale; the same family's stays until the new one is done.
            if (!shownFor.equals(pack + "|" + fam)) {
                shown = null;
                shownProbe = null;
            }
        }
        if (!probe.finished()) working = probe.step();
        else if (working != null && !working.done()) {
            // Some cases were still loading: try them all again in a second.
            long t = System.currentTimeMillis();
            if (retryAt == 0) retryAt = t + 1000;
            else if (t >= retryAt) {
                probe = new JointEditorScreen.Probe(pack, fam, level);
                retryAt = 0;
            }
        }
        if (working != null && working.done()) {
            shown = working;
            shownProbe = probe;
            shownFor = pack + "|" + fam;
        }
        return shown != null ? shown : working;
    }

    /** @return the current check's tally so far (null while loading or before the first frame) */
    static JointCount.Result current() {
        return shown != null ? shown : working;
    }

    /** Opens the joint editor on the first joint that doesn't fit (the tally shown's). */
    static void openFirst() {
        if (shownProbe != null) shownProbe.openFirstBad();
    }

    /** @return the timing of the current check: "steps=N totalMs=X maxStepMs=Y" */
    static String timing() {
        return probe == null ? "steps=0" : String.format("steps=%d totalMs=%.2f maxStepMs=%.2f", probe.steps, probe.nanos / 1e6, probe.maxNanos / 1e6);
    }
}
