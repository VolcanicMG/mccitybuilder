# Generates the Ponder help icons (copied into planner/Ponder.java) and checks every row is the right width: python3 tools/ponder_icons.py
# Topic icons (16x16), stage cursors (10x16) and cursor badges (9x9), one char per pixel ('.' clear); light from the top left.
# Also writes build/ponder_cursor_preview.png: arrow and pressed arrow with the move badge, 10x.
ICONS = {
"draw": [
"................", "................", "oooo..oooo..oooo", "olso..olso..olso", "olsSooolsSooolsS", "olllllllllllllso",
"olsssssssssssSSo", "olsSSssssSSssssS", "olssssssssssssSo", "olsssSSssssSSsSo", "olssssssssssssSo", "olSsssssSsssssss",
"oSSSSSSSSSSSSSSo", "oooooooooooooooo", "GgggggGgggggGggg", "................"],
"joints": [
"................", "oooooooooooooooo", "ollllobbbbollllo", "olsssobyybossSSo", "olsssobybBossSSo", "oSSSSobbBBoSSSSo",
"oooooobBBBoooooo", ".....olssSo.....", ".....olssSo.....", ".....olssSo.....", ".....olsSSo.....", ".....olssSo.....",
".....olssSo.....", ".....oSSSSo.....", ".....oooooo.....", "................"],
"connect": [
"................", "oooooooooooooo..", "ollllllllllllo..", "olsssssssssSSo..", "olsooooooooooo..", "olso........cc..", "olso............", "olso........cc..", "olso............", "olso........cc..", "olso............", "olso........cc..", "oSSo............", "oooo.cc.cc.cc...", "................", "................", 
],
"height": [
"......oo........", ".....owwo.......", "....owwwwo......", "...owwwwwwo.....", "...ooowwooo.....", ".....owwo.......",
".....owwo.......", ".....oooo.......", "...........ooooo", "...........oggGo", "......oooooodddo", "......oggggGdddo",
".oooooodddddddDo", ".oggggGddddddDDo", ".oddddddddDDDDDo", ".ooooooooooooooo"],
"gaps": [
"................", "................", "................", "oooooo....oooooo", "ollllo....ollllo", "olsSSo....osssSo",
"olsSSooooooosSSo", "olssSoyyyAosssSo", "olsSSoyaaAossSSo", "olssSoyaaAosssSo", "olsSSoAAAAossSSo", "oSSSSooooooSSSSo",
"oooooo....oooooo", "................", "................", "................"],
"edit": None,  # composed below: a node, the arrow on it, the move badge
"building": [
"................", ".oooooooooooooo.", ".owwwwwwwwwwwlo.", ".owlllllllllllS.", ".olllllllllllSo.", ".oSSSSSSSSSSSSo.", ".ooooobbBBooooo.", "......obbBo.....", "......obbBo.....", "......obbBo.....", "......obbBo.....", "oooooooooooooooo", "olllllllllllllSo", "olssSssssssSsssS", "oSSSSSSSSSSSSSSo", "oooooooooooooooo", 
],
"roads": [
"................", "ooooooo..ooo.ooo", "olswsSo..olo.olo", "olswsSo..olooolo", "olsssSo..ollllSo", "olsssSo..olssSSo", "olswsSo..olsSsso", "olswsSo..olssSSo", "olsssSo..olSssso", "olsssSo..olssSSo", "olswsSo..olsSsso", "olswsSo..olssSSo", "olsssSo..olsssso", "oSsssSo..oSSSSSo", "ooooooo..ooooooo", "................", 
],
}
CURSORS = {
"arrow": [
"o.........", "oo........", "owo.......", "owwo......", "owwwo.....", "owwwwo....", "owwwwwo...", "owwwwwwo..",
"owwwwwwwo.", "owwwwwoooo", "owwowwo...", "owo.owwo..", "oo..owwo..", "o....owwo.", ".....owwo.", "......oo.."],
"pressed": [
"..........", "o.........", "oo........", "olo.......", "ollo......", "olllo.....", "ollllo....", "olllllo...",
"ollllllo..", "olllllllo.", "ollllloooo", "ollolllo..", "olo.ollo..", "oo..ollo..", "o....ollo.", ".....oo..."],
}
# Badges (9x9) drawn at a cursor's lower right: "move" = the 4-way arrows of a drag. White shape given as a mask,
# the dark outline is every pixel beside it (4 neighbours).
def outlined(mask):
    n = len(mask)
    W_ = {(y, x) for y in range(n) for x in range(n) if mask[y][x] == 'w'}
    return ["".join('w' if (y, x) in W_ else 'o' if any((y + dy, x + dx) in W_ for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1))) else '.'
                    for x in range(n)) for y in range(n)]
BADGES = {
"move": outlined([
".........", "....w....", "...www...", "..w.w.w..", ".wwwwwww.", "..w.w.w..", "...www...", "....w....", "........."]),
}

def compose(layers, n=16):
    """Pixels from layers drawn in order ({(y, x): colour char}); every clear pixel 4-beside a filled one is outline."""
    px = {}
    for layer in layers: px.update(layer)
    return ["".join(px.get((y, x), 'o' if any((y + dy, x + dx) in px for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1))) else '.')
                    for x in range(n)) for y in range(n)]

def node(cy, cx, r):
    """An edit node: a diamond, light yellow top left, amber, dark amber bottom right."""
    out = {}
    for y in range(cy - r, cy + r + 1):
        for x in range(cx - r, cx + r + 1):
            d = abs(y - cy) + abs(x - cx)
            if d < r: out[(y, x)] = 'y' if (x - cx) + (y - cy) < -1 else 'A' if (x - cx) + (y - cy) > 1 else 'a'
    return out

def mask(rows, oy, ox, ch='w'):
    return {(oy + y, ox + x): ch for y, r in enumerate(rows) for x, c in enumerate(r) if c == 'w'}

# Edit: a node with the drag cursor's arrow on it and the move badge (CURSORS["arrow"] / BADGES["move"] in small).
ICONS["edit"] = compose([
    node(4, 4, 4),
    mask(["w", "ww", "www", "wwww", "ww"], 5, 5),
    mask(["...w...", "..www..", ".w.w.w.", "wwwwwww", ".w.w.w.", "..www..", "...w..."], 8, 8),
])

# Toolbar icons (not topics): a road in the wall icon's framing, and a blueprint sheet for Plan.
ICONS["tb_road"] = [
    "................", "................", "................", "oooooooooooooooo", "ollllllllllllllo", "oSSSSSSSSSSSSSSo",
    "osssssssssssssso", "osssssssssssssso", "osswwwsssswwwsso", "osssssssssssssso", "osssssssssssssso", "ollllllllllllllo",
    "oSSSSSSSSSSSSSSo", "oooooooooooooooo", "GgggggGgggggGggg", "................"]

def sheet():
    px = {}
    for y in range(1, 15):
        for x in range(1, 15):
            if x >= 11 and y <= 4 and (x - 11) + (4 - y) > 3: continue  # the folded corner is gone
            fold = x >= 11 and y <= 4 and (x - 11) + (4 - y) >= 2
            px[(y, x)] = 'l' if fold else 'c' if x % 5 == 3 or y % 5 == 3 else 'C'
    hut = [(4, 7), (5, 6), (5, 8), (6, 5), (6, 9), (7, 4), (7, 10)]
    hut += [(y, x) for y in range(8, 13) for x in (4, 10)] + [(12, x) for x in range(4, 11)] + [(7, x) for x in range(5, 10)]
    hut += [(11, 7), (10, 7)]
    for c in hut: px[c] = 'w'
    return px

ICONS["tb_plan"] = compose([sheet()])
import json, struct, zlib
for name, g in {**ICONS, **CURSORS}.items():
    w = 16 if name in ICONS else 10
    assert len(g) == 16, (name, len(g))
    for r in g: assert len(r) == w, (name, r, len(r))
for name, g in BADGES.items():
    assert len(g) == 9 and all(len(r) == 9 for r in g), name
    print(name, json.dumps(g))
json.dump({"icons": ICONS, "cursors": CURSORS, "badges": BADGES}, open('build/ponder_icons.json', 'w'), indent=0)

# The preview: on the stage's dark blue, each cursor with the badge at its lower right (as PonderScreen draws it).
# Ponder.iconColor's palette.
PAL = {'o': 0x202020, 'w': 0xFFFFFF, 'l': 0xC8C8C8, 's': 0x8E8E8E, 'S': 0x5C5C5C, 'b': 0xB58A55, 'B': 0x7A5530, 'y': 0xFFE680,
       'a': 0xFFB31A, 'A': 0xB07010, 'c': 0x40E6FF, 'C': 0x1E8FA8, 'g': 0x6CB44A, 'G': 0x3F7F2E, 'd': 0x8B5E3C, 'D': 0x5E3F27}
COL = {k: (v >> 16, v >> 8 & 255, v & 255) for k, v in PAL.items()}
BX, BY = 8, 9
W, H, S = 2 * 19 + 1, 20, 10
px = [[(0x2E, 0x39, 0x46)] * W for _ in range(H)]
for i, cur in enumerate(["arrow", "pressed"]):
    for g, ox, oy in ((CURSORS[cur], 1 + 19 * i, 1), (BADGES["move"], 1 + 19 * i + BX, 1 + BY)):
        for y, row in enumerate(g):
            for x, ch in enumerate(row):
                if ch in COL: px[oy + y][ox + x] = COL[ch]
def png(path, px, S):
    """Writes rows of RGB tuples, each pixel S x S."""
    W, H = len(px[0]), len(px)
    raw = b''.join(b'\0' + b''.join(bytes(px[y // S][x // S]) for x in range(W * S)) for y in range(H * S))
    chunk = lambda t, d: struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    open(path, 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W * S, H * S, 8, 2, 0, 0, 0))
                           + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))

def stage(n_w, n_h):
    return [[(0x2E, 0x39, 0x46)] * n_w for _ in range(n_h)]

def paint(px, g, ox, oy):
    for y, row in enumerate(g):
        for x, ch in enumerate(row):
            if ch in COL: px[oy + y][ox + x] = COL[ch]

png('build/ponder_cursor_preview.png', px, S)
# Every topic icon in a row (help order), and the edit icon alone.
ORDER = ["draw", "joints", "connect", "height", "gaps", "edit", "building", "roads", "tb_plan", "tb_road"]
row = stage(len(ORDER) * 18 + 2, 20)
for i, k in enumerate(ORDER): paint(row, ICONS[k], 2 + 18 * i, 2)
png('build/ponder_icons_preview.png', row, 10)
one = stage(20, 20)
paint(one, ICONS["edit"], 2, 2)
png('build/ponder_edit_icon.png', one, 10)
print("ok")
