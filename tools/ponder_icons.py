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
"edit": [
"................", "....oo..........", "...oyao.........", "..oyaaAo........", ".oyaaaaAo.......", "..oaaaAo........", "...oaAo.........", "....oo.ooo......", ".......owwo.....", "........owwo....", ".........owwooo.", "..........owwwo.", ".........owwwwo.", "........owwwwwo.", "........ooooooo.", "................", 
],
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
COL = {'o': (0x20, 0x20, 0x20), 'w': (0xFF, 0xFF, 0xFF), 'l': (0xC8, 0xC8, 0xC8)}
BX, BY = 8, 9
W, H, S = 2 * 19 + 1, 20, 10
px = [[(0x2E, 0x39, 0x46)] * W for _ in range(H)]
for i, cur in enumerate(["arrow", "pressed"]):
    for g, ox, oy in ((CURSORS[cur], 1 + 19 * i, 1), (BADGES["move"], 1 + 19 * i + BX, 1 + BY)):
        for y, row in enumerate(g):
            for x, ch in enumerate(row):
                if ch in COL: px[oy + y][ox + x] = COL[ch]
raw = b''.join(b'\0' + b''.join(bytes(px[y // S][x // S]) for x in range(W * S)) for y in range(H * S))
chunk = lambda t, d: struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
open('build/ponder_cursor_preview.png', 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W * S, H * S, 8, 2, 0, 0, 0))
                                                   + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))
print("ok")
