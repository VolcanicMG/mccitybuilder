# Generates the Ponder help icons (copied into planner/Ponder.java) and checks every row is the right width: python3 tools/ponder_icons.py
# Topic icons (16x16) and stage cursors (10x16), one char per pixel ('.' clear); light from the top left.
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
"hand": [
"....oo....", "...owwo...", "...owwo...", "...owwooo.", "...owwowwo", ".ooowwowwo", "owwowwwwwo", "owwwwwwwwo",
".owwwwwwwo", ".owwwwwwwo", "..owwwwwwo", "..owwwwwo.", "...owwwwo.", "...owwwwo.", "...oooooo.", ".........."],
}
import json
for name, g in {**ICONS, **CURSORS}.items():
    w = 16 if name in ICONS else 10
    assert len(g) == 16, (name, len(g))
    for r in g: assert len(r) == w, (name, r, len(r))
json.dump({"icons": ICONS, "cursors": CURSORS}, open('build/ponder_icons.json', 'w'), indent=0)
print("ok")
