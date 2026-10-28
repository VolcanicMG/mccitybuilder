package com.volcanicmg.mccitybuilder.server;

import com.volcanicmg.mccitybuilder.planner.PasteRules;
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
 * rotation ordinal, colony). Only an exact record may be deleted, moved or ordered removed, so a survival player can't
 * have a builder demolish an arbitrary shape; a record goes when its blocks are removed or the order is placed.
 */
public final class BuiltRecords extends SavedData {
    private static final String NAME = "mccitybuilder_built";
    private final List<String> keys = new ArrayList<>();

    private BuiltRecords() {
    }

    public static BuiltRecords of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(BuiltRecords::new, BuiltRecords::load, null), NAME);
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
        }
    }

    public boolean has(int colony, String pack, String path, BlockPos pos, int rot) {
        return keys.contains(PasteRules.recordKey(colony, pack, path, pos.getX(), pos.getY(), pos.getZ(), rot));
    }

    public void remove(int colony, String pack, String path, BlockPos pos, int rot) {
        if (keys.remove(PasteRules.recordKey(colony, pack, path, pos.getX(), pos.getY(), pos.getZ(), rot))) setDirty();
    }
}
