# City Builder for MineColonies

Formerly ColonyPlanner. Mod id `mccitybuilder`.

An overhead, RTS-style planner for [MineColonies](https://github.com/ldtteam/minecolonies) on NeoForge 1.21.1.
Press **J** (rebindable, "Planner view") while standing inside a colony you belong to (with hut access): the camera lifts above it while your character stays put. Outside a colony, or in someone else's, an action-bar message says why it won't open; a teleport out of the colony closes it.

- **Pan** with WASD or the screen edge, **zoom** with the wheel, **turn** with Q/E or right-drag (a right-click without dragging still backs out / deselects).
- **Place huts**: pick one in the palette (left; style pack and category pickers, greyed when you have none of that
  hut block), move the Structurize ghost with the mouse, R rotate, M mirror, PgUp/PgDn height, click to place. The
  hut goes through MineColonies' own build tool handling and a build request is sent for it. In creative the palette
  lists every hut with a level picker; placing, Upgrade and Repair paste at once and record the right level. On a
  dedicated server that needs City Builder installed on the server too (without it: placing pastes at level 1 and
  built huts get normal work orders).
- **Builder queue** (right): every work order with type, levels, builder and stage. Drag to reorder (sets
  priorities), B to hand it to the next builder, x to cancel.
- **Buildings**: click any part of a building on the map to select it: Upgrade (U), Repair (P), Open hut window (O).
- A **controls guide** (bottom right, ? hides it) lists the keys for what you are doing.

Everything goes through MineColonies' and Structurize's existing network messages and their server checks
(permissions, colony borders, hut item in inventory), so the server needs no extra mod logic.

Needs MineColonies 1.1.1403+, Structurize 1.0.832+ (and their dependencies).

## Building

`./gradlew build` (needs the exact MineColonies stack jars in the gitignored `libs/`, see `build.gradle`).
`check` runs `selfCheck` (camera, ray and queue math, guide layout, lang keys). `./gradlew plannerPreview -Pout=...`
renders a mock of the planner UI with the real vanilla font and sprites (read from the Minecraft client jar in the
Gradle cache). Design and the spike findings: [DESIGN.md](DESIGN.md).

## License

MIT
