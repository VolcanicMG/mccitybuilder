package com.volcanicmg.mccitybuilder.planner;

import java.util.List;
import java.util.Map;

/**
 * Palette categories for MineColonies' hut blocks, by block registry path ({@code minecolonies:blockhutbaker}).
 * Huts not listed (new or addon huts) land in "other". Pure.
 */
public final class HutCatalog {
    /** Category ids in palette order; lang keys are {@code mccitybuilder.category.<id>}. */
    public static final List<String> CATEGORIES = List.of("core", "production", "food", "military", "research", "services", "other");

    private static final Map<String, String> CATEGORY = Map.ofEntries(
            Map.entry("blockhuttownhall", "core"), Map.entry("blockhutbuilder", "core"), Map.entry("blockhutcitizen", "core"),
            Map.entry("blockhutwarehouse", "core"), Map.entry("blockhutdeliveryman", "core"), Map.entry("blockhuttavern", "core"),
            Map.entry("blockhutlumberjack", "production"), Map.entry("blockhutminer", "production"),
            Map.entry("blockhutsawmill", "production"), Map.entry("blockhutstonemason", "production"),
            Map.entry("blockhutsmeltery", "production"), Map.entry("blockhutstonesmeltery", "production"),
            Map.entry("blockhutblacksmith", "production"), Map.entry("blockhutcrusher", "production"),
            Map.entry("blockhutsifter", "production"), Map.entry("blockhutglassblower", "production"),
            Map.entry("blockhutdyer", "production"), Map.entry("blockhutfletcher", "production"),
            Map.entry("blockhutmechanic", "production"), Map.entry("blockhutconcretemixer", "production"),
            Map.entry("blockhutnetherworker", "production"), Map.entry("blockhutcomposter", "production"),
            Map.entry("blockhutplantation", "production"), Map.entry("blockhutflorist", "production"),
            Map.entry("blockhutfarmer", "food"), Map.entry("blockhutfisherman", "food"), Map.entry("blockhutbaker", "food"),
            Map.entry("blockhutcook", "food"), Map.entry("blockhutkitchen", "food"), Map.entry("blockhutchickenherder", "food"),
            Map.entry("blockhutcowboy", "food"), Map.entry("blockhutshepherd", "food"), Map.entry("blockhutswineherder", "food"),
            Map.entry("blockhutrabbithutch", "food"), Map.entry("blockhutbeekeeper", "food"), Map.entry("blockhutstable", "food"),
            Map.entry("blockhutguardtower", "military"), Map.entry("blockhutbarracks", "military"),
            Map.entry("blockhutbarrackstower", "military"), Map.entry("blockhutarchery", "military"),
            Map.entry("blockhutcombatacademy", "military"), Map.entry("blockhutgatehouse", "military"),
            Map.entry("blockhutuniversity", "research"), Map.entry("blockhutlibrary", "research"),
            Map.entry("blockhutschool", "research"), Map.entry("blockhutenchanter", "research"),
            Map.entry("blockhutalchemist", "research"), Map.entry("blockhutmysticalsite", "research"),
            Map.entry("blockhuthospital", "services"), Map.entry("blockhutgraveyard", "services"));

    private HutCatalog() {
    }

    /** @return the palette category of a hut block registry path */
    public static String category(String registryPath) {
        return CATEGORY.getOrDefault(registryPath, "other");
    }

    /** @return true for huts the planner never offers (placed by other means: fields, barracks towers) */
    public static boolean hidden(String registryPath) {
        return registryPath.equals("blockhutfield") || registryPath.equals("blockhutplantationfield")
                || registryPath.equals("blockhutbarrackstower")
                || !registryPath.startsWith("blockhut");
    }

    /** @return a hut blueprint path with its level digit set ({@code baker1.blueprint} → {@code baker3.blueprint}), or null without one */
    public static String levelPath(String path, int level) {
        if (path == null || !path.endsWith(".blueprint")) return null;
        String base = path.substring(0, path.length() - ".blueprint".length());
        if (base.isEmpty() || !Character.isDigit(base.charAt(base.length() - 1))) return null;
        return base.substring(0, base.length() - 1) + level + ".blueprint";
    }
}
