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
 * rotation ordinal, colony). Only an exact record (or, for decorations, a live decoration controller) may be deleted,
 * moved or ordered removed, so a survival player can't have a builder demolish an arbitrary shape; a record goes when
 * its blocks are removed or the order is placed. Changes are pushed to planners watching the colony.
 * Records placed since snapshots exist also keep their footprint box, so a restore never rewrites a cell another
 * placement still stands on.
 */
public final class BuiltRecords extends SavedData {
    private static final String NAME = "mccitybuilder_built";
    private final List<String> keys = new ArrayList<>();
    // Footprint boxes {x0, y0, z0, x1, y1, z1} per record key (records from before boxes were kept have none).
    private final java.util.Map<String, int[]> boxes = new java.util.HashMap<>();
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
        for (Tag t : tag.getList("boxes", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            int[] b = c.getIntArray("box");
            if (b.length == 6 && r.keys.contains(c.getString("key"))) r.boxes.put(c.getString("key"), b);
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider regs) {
        ListTag l = new ListTag();
        for (String k : keys) l.add(net.minecraft.nbt.StringTag.valueOf(k));
        tag.put("built", l);
        ListTag bl = new ListTag();
        boxes.forEach((k, b) -> {
            CompoundTag c = new CompoundTag();
            c.putString("key", k);
            c.putIntArray("box", b);
            bl.add(c);
        });
        tag.put("boxes", bl);
        return tag;
    }

    /** Remembers a decoration / piece as placed or ordered (idempotent). */
    public void add(int colony, String pack, String path, BlockPos pos, int rot) {
        String k = PasteRules.recordKey(colony, pack, path, pos.getX(), pos.getY(), pos.getZ(), rot);
        if (!keys.contains(k)) {
            keys.add(k);
            setDirty();
            changed(colony);
        }
    }

    /** {@link #add} with the placement's footprint box {x0, y0, z0, x1, y1, z1}. */
    public void add(int colony, String pack, String path, BlockPos pos, int rot, int[] box) {
        add(colony, pack, path, pos, rot);
        boxes.put(PasteRules.recordKey(colony, pack, path, pos.getX(), pos.getY(), pos.getZ(), rot), box.clone());
        setDirty();
    }

    /** @return record key → footprint box of every live record with a box but {@code except} (a record key) */
    public java.util.Map<String, int[]> otherBoxes(String except) {
        java.util.Map<String, int[]> out = new java.util.HashMap<>(boxes);
        out.remove(except);
        return out;
    }

    /**
     * @return the path of a record of {@code pack} at this anchor and turn whose path starts with {@code prefix} and
     * ends with {@code suffix} (a crop of a straight that may since have changed: its file name's source hash is
     * between the two), or null
     */
    public String recordedPath(int colony, String pack, String prefix, String suffix, BlockPos pos, int rot) {
        for (String k : keys) {
            PasteRules.Rec r = PasteRules.parseKey(k);
            if (r != null && r.colony() == colony && r.pack().equals(pack) && r.x() == pos.getX() && r.y() == pos.getY() && r.z() == pos.getZ() && r.rot() == rot
                    && r.path().startsWith(prefix) && r.path().endsWith(suffix)) return r.path();
        }
        return null;
    }

    public boolean has(int colony, String pack, String path, BlockPos pos, int rot) {
        return keys.contains(PasteRules.recordKey(colony, pack, path, pos.getX(), pos.getY(), pos.getZ(), rot));
    }

    public void remove(int colony, String pack, String path, BlockPos pos, int rot) {
        String k = PasteRules.recordKey(colony, pack, path, pos.getX(), pos.getY(), pos.getZ(), rot);
        boxes.remove(k);
        if (keys.remove(k)) {
            setDirty();
            changed(colony);
        }
    }

    /** @return the colony's records, decorations and road / wall pieces */
    public List<PasteRules.Rec> records(int colony) {
        List<PasteRules.Rec> out = new ArrayList<>();
        for (String k : keys) {
            PasteRules.Rec r = PasteRules.parseKey(k);
            if (r != null && r.colony() == colony) out.add(r);
        }
        return out;
    }

    private void changed(int colony) {
        if (level != null) DecoSync.changed(level, colony);
    }
}
