package com.volcanicmg.mccitybuilder.client;

import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.colony.workorders.IWorkOrderView;
import com.minecolonies.core.colony.buildings.moduleviews.BuildingResourcesModuleView;
import com.minecolonies.core.colony.buildings.moduleviews.WorkerBuildingModuleView;
import com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.volcanicmg.mccitybuilder.planner.Overlays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The planner's one combined overlay: huts by staffing, citizens as job-coloured markers, and builders as hammer
 * badges with their site outlined, a faint line from the hut and what they wait for. Data
 * comes from the client colony view (citizen views, work order views, the builder's resources module view, worker
 * module views) and is refreshed four times a second; positions come from the citizen entity when it is loaded.
 */
final class OverlayView {
    /** A builder at work: its hut, its site box, what it misses, and the builder citizen (may be null). */
    record Site(IBuildingView builder, BlockPos site, AABB box, List<Overlays.Need> needs, ICitizenDataView citizen) {
    }

    /** A citizen marker in the world: position, colour, the citizen, and its builder site for builders. */
    record Marker(Vec3 pos, int color, ICitizenDataView citizen, Site site) {
    }

    static List<Site> sites = List.of();
    static List<Marker> markers = List.of();
    static Map<BlockPos, Overlays.Staff> staff = Map.of();
    /** Hut staffing: {assigned, slots} per hut with job slots. */
    static Map<BlockPos, int[]> counts = Map.of();
    private static long lastRefresh;

    private OverlayView() {
    }

    /**
     * Rebuilds the combined overlay at most four times a second: builders (site, hammer badge, waiting materials),
     * then every other citizen as a job-coloured marker, and the huts' staffing. Builders are listed last so their
     * badges draw above the citizen dots.
     */
    static void refresh(IColonyView colony) {
        if (colony == null || System.currentTimeMillis() - lastRefresh < 250) return;
        lastRefresh = System.currentTimeMillis();
        List<Site> s = new ArrayList<>();
        List<Marker> builders = new ArrayList<>(), citizens = new ArrayList<>();
        Map<BlockPos, Overlays.Staff> st = new HashMap<>();
        Map<BlockPos, int[]> ct = new HashMap<>();
        java.util.Set<Integer> builderIds = new java.util.HashSet<>();
        for (IBuildingView b : colony.getClientBuildingManager().getBuildings().values()) {
            if (b instanceof BuildingBuilder.View) {
                ICitizenDataView who = null;
                for (int id : b.getAllAssignedCitizens()) {
                    who = colony.getCitizen(id);
                    builderIds.add(id);
                }
                IWorkOrderView order = null;
                for (IWorkOrderView o : colony.getWorkOrders()) if (o.isClaimed() && b.getID().equals(o.getClaimedBy())) order = o;
                List<Overlays.Need> needs = new ArrayList<>();
                BuildingResourcesModuleView res = b.getModuleViewByType(BuildingResourcesModuleView.class);
                if (res != null)
                    for (BuildingBuilderResource r : res.getResources().values()) {
                        var a = r.getAvailabilityStatus();
                        if (a == BuildingBuilderResource.RessourceAvailability.DONT_HAVE || a == BuildingBuilderResource.RessourceAvailability.NEED_MORE
                                || a == BuildingBuilderResource.RessourceAvailability.IN_DELIVERY)
                            needs.add(new Overlays.Need(r.getName(), r.getAmount() - r.getAvailable()));
                    }
                Site site = new Site(b, order == null ? null : order.getLocation(), order == null ? null : order.getBoundingBox(), Overlays.waiting(needs, 8), who);
                s.add(site);
                Vec3 at = where(who);
                if (at != null) builders.add(new Marker(at, 0xFFFFD040, who, site));
            }
            int slots = 0, assigned = 0;
            for (WorkerBuildingModuleView w : b.getModuleViews(WorkerBuildingModuleView.class)) {
                slots += w.getMaxInhabitants();
                assigned += w.getAssignedCitizens().size();
            }
            st.put(b.getID(), Overlays.staffing(assigned, slots));
            if (slots > 0) ct.put(b.getID(), new int[]{assigned, slots});
        }
        if (com.volcanicmg.mccitybuilder.Config.SHOW_CITIZENS.get())
            for (ICitizenDataView c : colony.getCitizens().values()) {
                if (builderIds.contains(c.getId())) continue;
                Vec3 at = where(c);
                if (at != null) citizens.add(new Marker(at, Overlays.jobColor(c.getJob()), c, null));
            }
        citizens.addAll(builders);
        sites = s;
        markers = citizens;
        staff = st;
        counts = ct;
    }

    /** @return the citizen's position: its entity when loaded (exact), else the last synced block position */
    private static Vec3 where(ICitizenDataView c) {
        if (c == null) return null;
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(c.getEntityId());
        if (e != null) return e.position().add(0, e.getBbHeight() + 0.3, 0);
        BlockPos p = c.getPosition();
        return p == null ? null : Vec3.atBottomCenterOf(p).add(0, 2.1, 0);
    }

    /** World part: builder sites (cyan box, a faint line from the hut) and under-staffed huts (red / amber boxes). */
    static void renderWorld(PoseStack pose, VertexConsumer lines) {
        for (Site s : sites) {
            if (s.site() == null) continue;
            AABB box = s.box() != null && s.box().getSize() > 0 ? s.box() : new AABB(s.site());
            LevelRenderer.renderLineBox(pose, lines, box.inflate(0.05), 0.3f, 0.85f, 1f, 0.8f);
            Vec3 a = Vec3.atCenterOf(s.builder().getPosition()).add(0, 1, 0), b = Vec3.atCenterOf(s.site()).add(0, 1, 0);
            var m = pose.last();
            Vec3 n = b.subtract(a).normalize();
            lines.addVertex(m, (float) a.x, (float) a.y, (float) a.z).setColor(0.3f, 0.85f, 1f, 0.35f).setNormal(m, (float) n.x, (float) n.y, (float) n.z);
            lines.addVertex(m, (float) b.x, (float) b.y, (float) b.z).setColor(0.3f, 0.85f, 1f, 0.35f).setNormal(m, (float) n.x, (float) n.y, (float) n.z);
        }
        if (!com.volcanicmg.mccitybuilder.Config.SHOW_STAFFING.get()) return;
        for (var en : staff.entrySet()) {
            IBuildingView b = Planner.colony() == null ? null : Planner.colony().getClientBuildingManager().getBuilding(en.getKey());
            if (b == null || en.getValue() == Overlays.Staff.FULL || en.getValue() == Overlays.Staff.NO_JOBS) continue;
            boolean none = en.getValue() == Overlays.Staff.NONE;
            LevelRenderer.renderLineBox(pose, lines, Planner.aabb(b).inflate(0.05), 1f, none ? 0.25f : 0.7f, none ? 0.25f : 0.15f, 1f);
        }
    }
}
