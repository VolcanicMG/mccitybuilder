package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Data shaping for the planner's combined overlay (staffing, citizens, builders). Pure. */
public final class Overlays {
    /** How a hut's job slots are filled. */
    public enum Staff { NO_JOBS, NONE, UNDER, FULL }

    /** One material a builder waits for: display name and how many are still missing. */
    public record Need(String name, int missing) {
    }

    // Job marker colours: vanilla-ish dye tones, picked by the job id so a job keeps its colour.
    private static final int[] JOB_COLORS = {0xFFE0A030, 0xFF50B050, 0xFF4080E0, 0xFFD05050, 0xFFB060D0, 0xFF40C0C0, 0xFFE070A0, 0xFFC0C040};

    private Overlays() {
    }

    public static Staff staffing(int assigned, int slots) {
        if (slots <= 0) return Staff.NO_JOBS;
        if (assigned <= 0) return Staff.NONE;
        return assigned < slots ? Staff.UNDER : Staff.FULL;
    }

    /** @return a stable marker colour for a job id; grey for none */
    public static int jobColor(String job) {
        return job == null || job.isEmpty() ? 0xFFA0A0A0 : JOB_COLORS[Math.floorMod(job.hashCode(), JOB_COLORS.length)];
    }

    /** @return the materials still missing (missing > 0), most missing first, at most {@code max} */
    public static List<Need> waiting(List<Need> rows, int max) {
        List<Need> out = new ArrayList<>();
        for (Need n : rows) if (n.missing() > 0) out.add(n);
        out.sort(Comparator.comparingInt(Need::missing).reversed().thenComparing(Need::name));
        return out.size() > max ? out.subList(0, max) : out;
    }

    /**
     * @param entityPresent the citizen's entity exists on this client
     * @param alive         and is alive and not removed
     * @return true to draw its marker. Without a live entity nothing is drawn: the citizen view's position is the
     * server's last known one, sent only with view updates, so it is stale (inside a building, asleep, moved,
     * despawned or dead) and would put markers where nobody is.
     */
    public static boolean showCitizen(boolean entityPresent, boolean alive) {
        return entityPresent && alive;
    }
}
