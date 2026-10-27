package com.volcanicmg.mccitybuilder.planner;

import java.util.Map;

/**
 * 9x9 pixel icons for work-order types in the queue panel, drawn at 1 texel = 1 GUI pixel ({@code #} = ink). Pure.
 */
public final class QueueIcons {
    public static final int SIZE = 9;
    public static final Map<String, String[]> ICONS = Map.ofEntries(
            // Hammer: a new building.
            Map.entry("build", new String[]{
                    ".#####...",
                    ".######..",
                    ".#####...",
                    "...#.....",
                    "...#.....",
                    "...#.....",
                    "...#.....",
                    "...#.....",
                    "........."}),
            // Up arrow: an upgrade.
            Map.entry("upgrade", new String[]{
                    "....#....",
                    "...###...",
                    "..#.#.#..",
                    ".#..#..#.",
                    "....#....",
                    "....#....",
                    "....#....",
                    "....#....",
                    "........."}),
            // Wrench: a repair.
            Map.entry("repair", new String[]{
                    "......##.",
                    ".....#..#",
                    ".....#.#.",
                    "....#.#..",
                    "...#.#...",
                    "..#.#....",
                    ".#.#.....",
                    "#.#......",
                    ".#......."}),
            // Cross: a removal.
            Map.entry("remove", new String[]{
                    "#.......#",
                    ".#.....#.",
                    "..#...#..",
                    "...#.#...",
                    "....#....",
                    "...#.#...",
                    "..#...#..",
                    ".#.....#.",
                    "#.......#"}),
            // Flower: a decoration.
            Map.entry("decoration", new String[]{
                    "....#....",
                    "...#.#...",
                    "....#....",
                    "....#....",
                    ".##.#.##.",
                    "..#.#.#..",
                    "...###...",
                    "....#....",
                    "..#####.."}));

    private QueueIcons() {
    }

    public static boolean ink(String icon, int x, int y) {
        String[] g = ICONS.get(icon);
        return g != null && g[y].charAt(x) == '#';
    }
}
