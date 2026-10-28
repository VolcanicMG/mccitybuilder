package com.volcanicmg.mccitybuilder.server;

import com.volcanicmg.mccitybuilder.planner.PasteRules;
import com.volcanicmg.mccitybuilder.planner.PieceSets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What City Builder itself placed or ordered in a dimension: decorations and road / wall pieces (pack, path, anchor,
 * rotation ordinal, colony). Only an exact record (or, for decorations, a live decoration controller) may be deleted,
 * moved or ordered removed, so a survival player can't have a builder demolish an arbitrary shape; a record goes when
 * its blocks are removed or the order is placed. Decoration changes are pushed to planners watching the colony.
 */
public final class BuiltRecords extends SavedData {
    private static final String NAME = "mccitybuilder_built";
    private final List<String> keys = new ArrayList<>();
    private ServerLevel level;

    private BuiltRecords() {
    }

    public static BuiltRecords of(ServerLevel level) {
        BuiltRecords r = level.getDataStorage().computeIfAbsent(new Factory<>(BuiltRecords::new, BuiltRecords::load, null), NAME);
        r.level = level;
        return r;
    }

    private static BuiltRecords load(CompoundTag tag, HolderLookup.Provider regs) {
        BuiltRecords r = new BuiltRecords();
        for (Tag t : tag.getList("built", Tag.TAG_STRING)) r.keys.add(t.getAsString());
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider regs) {
        ListTag l = new ListTag();
        for (String k : keys) l.add(net.minecraft.nbt.StringTag.valueOf(k));
        tag.put("built", l);
        return tag;
    }

    /** Remembers a decoration / piece as placed or ordered (idempotent). */
    public void add(int colony, String pack, String path, BlockPos pos, int rot) {
        String k = PasteRules.recordKey(colony, pack, path, pos.getX(), pos.getY(), pos.getZ(), rot);
        if (!keys.contains(k)) {
            keys.add(k);
            setDirty();
            changed(colony, path);
        }
    }

    public boolean has(int colony, String pack, String path, BlockPos pos, int rot) {
        return keys.contains(PasteRules.recordKey(colony, pack, path, pos.getX(), pos.getY(), pos.getZ(), rot));
    }

    public void remove(int colony, String pack, String path, BlockPos pos, int rot) {
        if (keys.remove(PasteRules.recordKey(colony, pack, path, pos.getX(), pos.getY(), pos.getZ(), rot))) {
            setDirty();
            changed(colony, path);
        }
    }

    /** @return the colony's decoration records (road / wall pieces left out: the line tools own those) */
    public List<PasteRules.Rec> decorations(int colony) {
        List<PasteRules.Rec> out = new ArrayList<>();
        for (String k : keys) {
            PasteRules.Rec r = PasteRules.parseKey(k);
            if (r != null && r.colony() == colony && PieceSets.tool(r.path()) == null) out.add(r);
        }
        return out;
    }

    private void changed(int colony, String path) {
        if (level != null && PieceSets.tool(path == null ? "" : path) == null) DecoSync.changed(level, colony);
    }
}
