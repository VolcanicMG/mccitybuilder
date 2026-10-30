package com.volcanicmg.mccitybuilder.client;

import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.colony.interactionhandling.ChatPriority;
import com.minecolonies.api.colony.interactionhandling.IInteractionResponseHandler;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.api.util.constant.CitizenConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * The citizen marker's hover card, drawn by hand in the vanilla tooltip frame: job / hut icon and name, a red
 * "can't work" strip (MineColonies' open inquiries, blocking first, then unemployed / homeless / sick / hungry /
 * unhappy), a status chip, one bar row per stat (health, food, happiness, the job's two skills), and a footer with
 * what they hold and wear, and a builder's site and needs.
 */
final class CitizenCard {
    static final int W = 232, PAD = 4, ROW = 11, BAR_W = 60, MAX_PROBLEMS = 4;
    private static final ResourceLocation HEART = ResourceLocation.withDefaultNamespace("hud/heart/full"),
            FOOD = ResourceLocation.withDefaultNamespace("hud/food_full"),
            HAPPY = ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/happy_icon.png"),
            UNHAPPY = ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/unhappy_icon.png");

    /** The full card without Shift held (the dev bridge's screenshots). */
    static boolean showFull;

    private CitizenCard() {
    }

    /** A stat row: icon (sprite, texture, or none), label, bar fill 0..1 and colour, value text. */
    private record Stat(ResourceLocation sprite, ResourceLocation texture, Component label, double fill, int color, String value) {
    }

    /** A happiness factor chip (MineColonies' name, colour by how it acts, sort order: hurting first) or a skill cell. */
    private record Chip(String text, int color, int order) {
    }

    /** What the card shows, worked out once per frame. */
    private record Content(ItemStack icon, Component name, Component sub, List<MutableComponent> problems, int more, Component status, int statusColor,
                           List<Stat> stats, List<Chip> factors, List<Chip> skills, List<ItemStack> wear, List<Component> footer) {
    }

    /**
     * @return the citizen's happiness factors as MineColonies' citizen window rates them (factor above 1 helps, 1
     * is neutral, below hurts; below 0.75 hurts a lot), hurting ones first; neutral ones only with {@code all}
     */
    private static List<Chip> factors(ICitizenDataView c, boolean all) {
        List<Chip> out = new ArrayList<>();
        var hh = c.getHappinessHandler();
        if (hh == null) return out;
        for (String id : hh.getModifiers()) {
            double f;
            try {
                f = hh.getModifier(id).getFactor(null);
            } catch (RuntimeException e) {
                continue;
            }
            int color = f > 1 ? 0xFF3C9A3C : f == 1 ? 0xFF5A5A5A : f >= 0.75 ? 0xFFB07A20 : 0xFFC03030, order = f > 1 ? 2 : f == 1 ? 3 : f >= 0.75 ? 1 : 0;
            if (order == 3 && !all) continue;
            out.add(new Chip(Component.translatable("com.minecolonies.coremod.gui.townhall.happiness." + id).getString(), color, order));
        }
        out.sort(java.util.Comparator.comparingInt(Chip::order));
        return out;
    }

    /** @return MineColonies' food wording for a saturation (its own LOW / AVERAGE / FULL thresholds) */
    static String foodKey(double sat) {
        return sat <= 0 ? "starving" : sat < CitizenConstants.LOW_SATURATION ? "hungry" : sat < CitizenConstants.AVERAGE_SATURATION ? "peckish"
                : sat < CitizenConstants.FULL_SATURATION * 0.75 ? "fed" : "full";
    }

    /** @return the happiness wording for MineColonies' 0..10 score */
    static String happyKey(double h) {
        return h < 3 ? "unhappy" : h < 6 ? "content" : h < 8 ? "happy" : "very_happy";
    }

    private static Content content(OverlayView.Marker m, boolean full) {
        ICitizenDataView c = m.citizen();
        IColonyView colony = Planner.colony();
        IBuildingView work = colony == null || c == null || c.getWorkBuilding() == null ? null : colony.getClientBuildingManager().getBuilding(c.getWorkBuilding());
        ItemStack icon = m.mainIcon() != null ? m.mainIcon() : ItemStack.EMPTY;
        Component name = Component.literal(c == null ? "?" : c.getName()).withStyle(ChatFormatting.BOLD);
        List<MutableComponent> problems = new ArrayList<>();
        List<Stat> stats = new ArrayList<>();
        List<Component> footer = new ArrayList<>();
        List<Chip> factors = new ArrayList<>(), skills = new ArrayList<>();
        Component sub = Component.empty(), status = null;
        int statusColor = 0;
        if (c != null) {
            boolean child = c.isChild();
            sub = child ? Component.translatable("mccitybuilder.citizen.child")
                    : work == null ? Component.translatable("mccitybuilder.citizen.no_job")
                    : c.getJobComponent().copy().append(Component.literal(" · ")).append(Component.translatable(work.getBuildingDisplayName())).append(Component.literal(" " + work.getBuildingLevel()));
            List<IInteractionResponseHandler> asks = new ArrayList<>(c.getOrderedInteractions());
            // Blocking first; chit-chat (wishes, greetings) and hidden ones aren't reasons it can't work.
            asks.removeIf(h -> h.getPriority().getPriority() <= ChatPriority.CHITCHAT.getPriority());
            asks.sort((x, y) -> Integer.compare(y.getPriority().getPriority(), x.getPriority().getPriority()));
            for (var h : asks) if (h.getInquiry() != null) problems.add(h.getInquiry().copy());
            if (!child && (c.getJob() == null || c.getJob().isEmpty() || work == null)) problems.add(Component.translatable("mccitybuilder.citizen.unemployed"));
            if (c.getHomeBuilding() == null || c.getHomeBuilding().equals(BlockPos.ZERO)) problems.add(Component.translatable("mccitybuilder.citizen.homeless"));
            if (c.isSick()) problems.add(Component.translatable("mccitybuilder.citizen.sick"));
            if (c.getSaturation() < CitizenConstants.LOW_SATURATION) problems.add(Component.translatable("mccitybuilder.citizen." + foodKey(c.getSaturation())));
            if (c.getHappiness() < 3) problems.add(Component.translatable("mccitybuilder.citizen.unhappy_reason"));
            VisibleCitizenStatus vs = c.getVisibleStatus();
            if (vs != null) {
                status = Component.translatable(vs.getTranslationKey());
                statusColor = vs == VisibleCitizenStatus.WORKING ? 0xFF3C9A3C : vs == VisibleCitizenStatus.SLEEP ? 0xFF3A6FC4 : vs == VisibleCitizenStatus.SICK ? 0xFFC03030 : 0xFFC08A20;
            }
            double hp = c.getMaxHealth() <= 0 ? 0 : c.getHealth() / c.getMaxHealth();
            stats.add(new Stat(HEART, null, Component.translatable("mccitybuilder.citizen.health"), hp, 0xFFD03030, (int) Math.ceil(c.getHealth()) + "/" + (int) Math.ceil(c.getMaxHealth())));
            double sat = c.getSaturation() / CitizenConstants.FULL_SATURATION;
            stats.add(new Stat(FOOD, null, Component.translatable("mccitybuilder.citizen.food"), sat, 0xFFC88A3A,
                    Component.translatable("mccitybuilder.citizen." + foodKey(c.getSaturation())).getString()));
            double h = c.getHappiness();
            stats.add(new Stat(null, h < 3 ? UNHAPPY : HAPPY, Component.translatable("com.minecolonies.coremod.gui.citizen.happiness"), h / 10,
                    h < 3 ? 0xFFC03030 : h < 6 ? 0xFFC08A20 : 0xFF3C9A3C, Component.translatable("mccitybuilder.citizen." + happyKey(h)).getString()));
            factors.addAll(factors(c, full));
            // The job's primary and secondary skill (the workplace module for this job) as bars; with Shift every skill
            // in a grid, the job's two first and highlighted. Children and the unemployed: the grid only with Shift.
            Skill[] sk = new Skill[2];
            if (!child && work != null)
                for (var w : work.getModuleViews(com.minecolonies.core.colony.buildings.moduleviews.WorkerBuildingModuleView.class))
                    if (sk[0] == null || c.getJobView() != null && w.getJobEntry() == c.getJobView().getEntry()) {
                        sk[0] = w.getPrimarySkill();
                        sk[1] = w.getSecondarySkill();
                    }
            var handler = c.getCitizenSkillHandler();
            if (handler != null) {
                for (Skill s : sk)
                    if (s != null) {
                        int lv = handler.getLevel(s);
                        stats.add(new Stat(null, null, Component.literal("» ").append(Component.translatable("com.minecolonies.coremod.gui.citizen.skills." + s.name().toLowerCase(Locale.ROOT))),
                                lv / 99.0, 0xFFE0B040, String.valueOf(lv)));
                    }
                if (full)
                    for (Skill s : Skill.values())
                        if (s != sk[0] && s != sk[1])
                            skills.add(new Chip(Component.translatable("com.minecolonies.coremod.gui.citizen.skills." + s.name().toLowerCase(Locale.ROOT)).getString()
                                    + " " + handler.getLevel(s), 0xFFA0A0A0, 0));
            }
        }
        List<ItemStack> wear = new ArrayList<>();
        if (!m.held().isEmpty()) {
            wear.add(m.held());
            footer.add(Component.translatable("mccitybuilder.overlay.holding", m.held().getHoverName()));
        }
        if (!m.armour().isEmpty()) {
            wear.add(m.armour());
            String tier = m.armour().getHoverName().getString().split(" ")[0];
            footer.add(Component.translatable("mccitybuilder.overlay.armour", tier, m.armourPieces()));
        }
        if (m.site() != null) {
            if (m.icon() != null) footer.add(Component.translatable("mccitybuilder.overlay.needs", m.icon().getHoverName()).withStyle(ChatFormatting.RED));
            footer.add(m.site().site() == null ? Component.translatable("mccitybuilder.overlay.idle") : Component.translatable("mccitybuilder.overlay.site", m.site().site().toShortString()));
            if (!m.site().needs().isEmpty()) footer.add(Component.translatable("mccitybuilder.overlay.waiting").withStyle(ChatFormatting.GOLD));
            for (var n : m.site().needs()) footer.add(Component.literal(" " + n.missing() + " x " + n.name()));
        } else if (!Planner.remoteOpenOff()) footer.add(Component.translatable("mccitybuilder.overlay.click_citizen").withStyle(ChatFormatting.DARK_GRAY));
        int more = Math.max(0, problems.size() - MAX_PROBLEMS);
        if (!full && c != null) footer.add(Component.translatable("mccitybuilder.citizen.shift").withStyle(ChatFormatting.DARK_GRAY));
        return new Content(icon, name, sub, problems.subList(0, Math.min(MAX_PROBLEMS, problems.size())), more, status, statusColor, stats, factors, skills, wear, footer);
    }

    /** @return how many rows {@link #chips} takes for these chips in {@code w} px */
    private static int chipRows(Font font, List<Chip> chips, int w) {
        if (chips.isEmpty()) return 0;
        int rows = 1, x = 0;
        for (Chip c : chips) {
            int cw = font.width(c.text()) + 6;
            if (x > 0 && x + cw > w) {
                rows++;
                x = 0;
            }
            x += cw + 2;
        }
        return rows;
    }

    /** Draws chips left to right, wrapping at {@code w}. @return the y below them */
    private static int chips(GuiGraphics g, Font font, List<Chip> chips, int x0, int y, int w) {
        if (chips.isEmpty()) return y;
        int x = 0;
        for (Chip c : chips) {
            int cw = font.width(c.text()) + 6;
            if (x > 0 && x + cw > w) {
                y += 11;
                x = 0;
            }
            g.fill(x0 + x, y, x0 + x + cw, y + 10, c.color());
            g.drawString(font, c.text(), x0 + x + 3, y + 1, 0xFFFFFFFF, false);
            x += cw + 2;
        }
        return y + 11;
    }

    /**
     * Draws the card beside the cursor, kept on screen (flips left of / above the cursor near the edges).
     *
     * @param itemBudget item models it may still draw this frame (the overlay's per-frame cap); icons past it are skipped
     */
    static void draw(GuiGraphics g, Font font, OverlayView.Marker m, int mx, int my, int sw, int sh, int itemBudget) {
        Content ct = content(m, net.minecraft.client.gui.screens.Screen.hasShiftDown() || showFull);
        int inner = W - 2 * PAD;
        List<FormattedCharSequence> probLines = new ArrayList<>();
        for (MutableComponent p : ct.problems()) probLines.addAll(font.split(p, inner - 12));
        if (ct.more() > 0) probLines.add(Component.translatable("mccitybuilder.citizen.more", ct.more()).getVisualOrderText());
        List<FormattedCharSequence> foot = new ArrayList<>();
        for (Component f : ct.footer()) foot.addAll(font.split(f, inner - (ct.wear().isEmpty() ? 0 : 0)));
        int chipRows = chipRows(font, ct.factors(), inner - 12), skillRows = (ct.skills().size() + 1) / 2;
        int h = 20 + (probLines.isEmpty() ? 0 : probLines.size() * 10 + 6) + (ct.status() == null ? 0 : 14) + ct.stats().size() * ROW + chipRows * 11 + skillRows * 10 + 2
                + (ct.wear().isEmpty() ? 0 : 18) + foot.size() * 10;
        int x = mx + 12, y = my - 12;
        if (x + W > sw - 4) x = mx - 12 - W;
        if (y + h > sh - 4) y = Math.max(4, my - h - 4);
        if (x < 4) x = 4;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        TooltipRenderUtil.renderTooltipBackground(g, x, y, W, h, 0);
        int cy = y;
        // Header: the job's / hut's item, the name in bold, the job and workplace underneath.
        if (!ct.icon().isEmpty() && itemBudget-- > 0) {
            g.pose().pushPose();
            g.pose().translate(0, 0, -150);
            g.renderItem(ct.icon(), x, cy + 1);
            g.pose().popPose();
        }
        int tx = ct.icon().isEmpty() ? x : x + 19;
        g.drawString(font, ct.name(), tx, cy, 0xFFFFFFFF, false);
        g.drawString(font, font.plainSubstrByWidth(ct.sub().getString(), x + W - tx), tx, cy + 10, 0xFFA0A0A0, false);
        cy += 20;
        // "Can't work": a red strip with a warning glyph.
        if (!probLines.isEmpty()) {
            int sh2 = probLines.size() * 10 + 4;
            g.fill(x - 2, cy, x + W + 2, cy + sh2, 0x60A01818);
            g.fill(x, cy + 2, x + 9, cy + 11, 0xFFD03030);
            g.drawString(font, "!", x + 3, cy + 3, 0xFFFFFFFF, false);
            for (int i = 0; i < probLines.size(); i++) g.drawString(font, probLines.get(i), x + 12, cy + 2 + i * 10, 0xFFFF7070, false);
            cy += sh2 + 2;
        }
        // Status chip.
        if (ct.status() != null) {
            String s = ct.status().getString();
            int cw = font.width(s) + 8;
            g.fill(x, cy, x + cw, cy + 11, ct.statusColor());
            g.drawString(font, s, x + 4, cy + 2, 0xFFFFFFFF, false);
            cy += 14;
        }
        // One row per stat: icon, label, bar, value; the happiness factors right under the happiness row.
        int si = 0;
        for (Stat st : ct.stats()) {
            if (st.sprite() != null) g.blitSprite(st.sprite(), x, cy, 9, 9);
            else if (st.texture() != null) g.blit(st.texture(), x, cy, 0, 0, 9, 9, 9, 9);
            g.drawString(font, font.plainSubstrByWidth(st.label().getString(), 66), x + 12, cy + 1, 0xFFC0C0C0, false);
            int bx = x + 80;
            g.fill(bx, cy + 2, bx + BAR_W, cy + 7, 0xFF303030);
            g.fill(bx, cy + 2, bx + (int) Math.round(BAR_W * Math.max(0, Math.min(1, st.fill()))), cy + 7, st.color());
            g.drawString(font, font.plainSubstrByWidth(st.value(), x + W - bx - BAR_W - 4), bx + BAR_W + 4, cy + 1, 0xFFE0E0E0, false);
            cy += ROW;
            if (++si == 3) cy = chips(g, font, ct.factors(), x + 12, cy, inner - 12);
        }
        // Every other skill (Shift), two columns.
        for (int i = 0; i < ct.skills().size(); i++) {
            Chip k = ct.skills().get(i);
            g.drawString(font, font.plainSubstrByWidth(k.text(), inner / 2 - 4), x + (i % 2) * (inner / 2), cy, k.color(), false);
            if (i % 2 == 1 || i == ct.skills().size() - 1) cy += 10;
        }
        cy += 2;
        // Footer: held and worn items, then text (holding, armour, a builder's site and needs).
        if (!ct.wear().isEmpty()) {
            int ix = x;
            for (ItemStack st : ct.wear())
                if (itemBudget-- > 0) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, -150);
                    g.renderItem(st, ix, cy);
                    g.pose().popPose();
                    ix += 18;
                }
            cy += 18;
        }
        for (FormattedCharSequence f : foot) {
            g.drawString(font, f, x, cy, 0xFFA0A0A0, false);
            cy += 10;
        }
        g.pose().popPose();
    }
}
