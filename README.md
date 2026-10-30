# City Builder for MineColonies

**Plan and build your MineColonies town from the sky.** Press **J** inside your colony and the camera lifts into an
overhead, city-builder view: place huts and decorations, draw walls and roads, and run your builders, all through
MineColonies' own build and work-order system.

NeoForge 1.21.1 · MineColonies addon · **Beta** · MIT

---

## What it does

| | |
|---|---|
| **Overhead camera** | Pan, zoom, turn and tilt over your colony while your character stays put. The camera glides over the terrain and keeps to your colony. |
| **Place huts and decorations** | Pick from your style packs, preview with Structurize's ghost, rotate, mirror, nudge, then place. Survival places the hut and sends a build request; creative pastes it instantly at any level. |
| **Walls and roads** | Draw segments with your style pack's real wall and road pieces. Corners, T-junctions and crossroads join automatically, loops close with one click, and **Fill gaps** closes the cells whole pieces can't reach. |
| **Builder queue** | Every work order with its builder, stage and progress. Drag to reorder, assign the next builder, or cancel. |
| **Manage buildings** | Click any building to upgrade, repair, move (creative) or remove it. In survival, Upgrade opens MineColonies' own window with the materials and builder pick. |
| **See your colony** | Citizen markers show what each worker holds and wears. Hover one for a card with what's stopping them working, their status, health, food, happiness factors and job skills. Selecting a hut draws lines to its workers. |
| **Plan mode** | Lay out a whole district first, then build it all, or one piece at a time. |
| **Built-in help** | A 3D, step-by-step guide for walls and roads, and a controls panel that shows only the keys for what you're doing. |
| **Multiplayer** | Colony members share built walls and roads, every action is checked by the server against MineColonies permissions, and admins get settings for survival access. |

## Requirements

- Minecraft **1.21.1**, NeoForge **21.1.262+**
- **MineColonies 1.1.1403+** and **Structurize 1.0.832+** (with their own dependencies)
- Install City Builder on **both client and server**. A server without it still lets you plan and send build
  requests, but creative instant tools and wall/road sharing need it there.

## Getting started

1. Stand inside a colony where you have hut access and press **J** ("Planner view", rebindable).
2. Pick a hut from the palette on the left, move it into place, and click.
3. Press **N** for the road tool or **B** for the wall tool, click to start a segment, and click again to drop it.
4. Open **?** on the road/wall panel for the step-by-step guide.

Press **J** again to come back down.

## Main controls

| Key | Action |
|---|---|
| WASD / screen edge | Pan (Shift: faster) |
| Wheel | Zoom |
| Q / E, right-drag | Turn (and tilt) |
| V | Top-down view |
| R / M | Rotate / mirror the piece you're placing |
| PgUp / PgDn | Raise / lower |
| U / P | Upgrade / repair the selected building |
| K | Plan mode |
| N / B | Road / wall tool |
| F | Fill gaps |
| Backspace | Undo |

The controls panel (bottom right) always lists the keys for what you're doing right now.

## Creative and survival

- **Survival** goes through MineColonies like playing by hand: you need the hut item, builders do the work, and
  materials come from your colony.
- **Creative** adds instant tools: place, upgrade, repair, move and remove buildings and decorations at once.
  Removing restores the ground that was there before, and containers keep or return their items.

## Server settings

`world/serverconfig/mccitybuilder-server.toml`, changes apply without a restart:

| Setting | Default | What it does |
|---|---|---|
| `plannerRemoteInventories` | `false` | Lets survival players open hut and citizen windows (and their inventories) from the overhead view. |
| `strictInventoryReach` | `false` | Closes any hut, rack, grave or citizen inventory a survival player opens from beyond reach, including MineColonies' own remote windows. |

Camera speed, edge panning, terrain following and the overlays are in the client config (`mccitybuilder-client.toml`).

## Beta

City Builder is in beta: it works and is tested in survival, creative and on dedicated servers, but expect rough
edges. Please report bugs and ideas on the [issue tracker](https://github.com/VolcanicMG/mccitybuilder/issues).
Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## Building from source

`./gradlew build`. It needs the exact MineColonies, Structurize, Domum Ornamentum and BlockUI jars in the gitignored
`libs/` folder (see `build.gradle`). `check` runs a self-check of the layout, camera, joint and help-script logic.
Design notes: [DESIGN.md](DESIGN.md).

## License

[MIT](LICENSE)
