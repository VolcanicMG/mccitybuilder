package com.volcanicmg.mccitybuilder.planner;

import java.util.Map;

/**
 * 16x16 pixel-art icons for the placement buttons (action bar and height buttons), drawn at 1 texel = 1 GUI pixel so
 * they stay crisp at any GUI scale: a dark outline around a light body, shaded on its lower edge. Pure.
 */
public final class ActionIcons {
    public static final int SIZE = 16;
    /** Palette: {@code o} outline, {@code w}/{@code l} light body / shade, {@code g}/{@code G} green, {@code r}/{@code R} red. */
    public static final Map<Character, Integer> COLORS = Map.of('o', 0xFF1E1E1E, 'w', 0xFFFFFFFF, 'l', 0xFFB8B8B8,
            'g', 0xFF6CE05A, 'G', 0xFF2F8F2A, 'r', 0xFFFF5C5C, 'R', 0xFFA82222);
    public static final Map<String, String[]> ICONS = Map.of(
            "place", new String[]{
                    "................",
                    "................",
                    "................",
                    "...........oooo.",
                    "..........ooggo.",
                    ".........ooggGo.",
                    "........ooggGoo.",
                    "..oooo..oggGoo..",
                    "..oggooooggoo...",
                    "..oGggooggGo....",
                    "..ooGggggGoo....",
                    "...ooGggGoo.....",
                    "....ooGGoo......",
                    ".....oooo.......",
                    "................",
                    "................"},
            "rotate", new String[]{
                    "................",
                    ".......ooo......",
                    ".......owoo.....",
                    "....oooowwoo....",
                    "...oowwwwwwoo...",
                    "..oowlllwwllo...",
                    ".oowlooowlooo...",
                    ".owwoo.oloo.....",
                    ".owwo..ooo.oooo.",
                    ".owwo......owwo.",
                    ".olwoo....oowlo.",
                    ".oolwoooooowloo.",
                    "..oolwwwwwwloo..",
                    "...oolllllloo...",
                    "....oooooooo....",
                    "................"},
            "mirror", new String[]{
                    "ooo....w....ooo.",
                    "owo....w....owo.",
                    "owoo...o...oowo.",
                    "owwoo..w..oowwo.",
                    "owwwoo.w.oowwwo.",
                    "owwwwooooowwwwo.",
                    "owwwwwowowwwwwo.",
                    "owwwwwowowwwwwo.",
                    "owwwwwooowwwwwo.",
                    "owwwwlowolwwwwo.",
                    "owwwloowoolwwwo.",
                    "owwloo.o.oolwwo.",
                    "owloo..w..oolwo.",
                    "owoo...w...oowo.",
                    "olo....o....olo.",
                    "ooo....w....ooo."},
            "up", new String[]{
                    "................",
                    "................",
                    "................",
                    "......oooo......",
                    "......owwo......",
                    ".....oowwoo.....",
                    "....oowwwwoo....",
                    "...oowwwwwwoo...",
                    "..oowwwwwwwwoo..",
                    ".oowwwwwwwwwwoo.",
                    ".ollllllllllllo.",
                    ".oooooooooooooo.",
                    "................",
                    "................",
                    "................",
                    "................"},
            "down", new String[]{
                    "................",
                    "................",
                    "................",
                    "................",
                    ".oooooooooooooo.",
                    ".olwwwwwwwwwwlo.",
                    ".oolwwwwwwwwloo.",
                    "..oolwwwwwwloo..",
                    "...oolwwwwloo...",
                    "....oolwwloo....",
                    ".....oowwoo.....",
                    "......ollo......",
                    "......oooo......",
                    "................",
                    "................",
                    "................"},
            "cancel", new String[]{
                    "................",
                    "................",
                    "..oooo....oooo..",
                    "..orroo..oorro..",
                    "..oRrroooorrRo..",
                    "..ooRrroorrRoo..",
                    "...ooRrrrrRoo...",
                    "....oorrrroo....",
                    "....oorrrroo....",
                    "...oorrRRrroo...",
                    "..oorrRooRrroo..",
                    "..orrRooooRrro..",
                    "..oRRoo..ooRRo..",
                    "..oooo....oooo..",
                    "................",
                    "................"});

    private ActionIcons() {
    }

    /** @return the ARGB colour at (x, y), or 0 where the icon is transparent */
    public static int color(String icon, int x, int y) {
        String[] g = ICONS.get(icon);
        return g == null ? 0 : COLORS.getOrDefault(g[y].charAt(x), 0);
    }
}
