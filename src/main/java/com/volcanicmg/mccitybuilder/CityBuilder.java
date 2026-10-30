package com.volcanicmg.mccitybuilder;

import com.mojang.logging.LogUtils;
import com.volcanicmg.mccitybuilder.client.PlannerClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * City Builder for MineColonies (formerly ColonyPlanner): an overhead planner for MineColonies. Everything it does goes through MineColonies' and
 * Structurize's own messages (build tool placement, build requests, work order changes). The one server-side part,
 * {@link com.volcanicmg.mccitybuilder.server.InstantBuild} (instant creative builds), is optional on both ends.
 */
@Mod(CityBuilder.MODID)
public final class CityBuilder {
    public static final String MODID = "mccitybuilder";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CityBuilder(IEventBus modBus, ModContainer container) {
        migrateConfig();
        container.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        container.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
        modBus.addListener(com.volcanicmg.mccitybuilder.server.InstantBuild::register);
        com.volcanicmg.mccitybuilder.server.InstantBuild.init();
        if (FMLEnvironment.dist == Dist.CLIENT) PlannerClient.init(modBus, container);
    }

    /** The mod was called ColonyPlanner: its client config is copied to the new name once, before it loads. */
    private static void migrateConfig() {
        try {
            java.nio.file.Path dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get(), old = dir.resolve("colonyplanner-client.toml"), now = dir.resolve(MODID + "-client.toml");
            if (java.nio.file.Files.exists(old) && !java.nio.file.Files.exists(now)) java.nio.file.Files.copy(old, now);
        } catch (Exception e) {
            LOGGER.warn("City Builder: couldn't copy the old ColonyPlanner config", e);
        }
    }
}
