# Store listings (CurseForge + Modrinth)

Copy-paste source for both project pages. Keep the two in step when features change.

## Basics

| Field | Value |
|---|---|
| Name | City Builder for MineColonies |
| Slug | `city-builder-for-minecolonies` (CurseForge) / `mccitybuilder` (Modrinth) |
| Summary (CurseForge, max 256) | Plan and build your MineColonies town from the sky: an overhead city-builder view to place huts and decorations, draw walls and roads with your style pack's pieces, and run your builders. Beta. |
| Summary (Modrinth, max ~250) | Same as above. |
| Release type | **Beta** |
| Version | 1.0.0-beta.1 |
| Game version / loader | 1.21.1 / NeoForge |
| Environment (Modrinth) | Client: required · Server: required |
| License | MIT |
| Source | https://github.com/VolcanicMG/mccitybuilder |
| Issues | https://github.com/VolcanicMG/mccitybuilder/issues |
| Dependencies | MineColonies (required), Structurize (required) |
| CurseForge categories | Map and Information, Utility & QoL, Addons (MineColonies if offered) |
| Modrinth categories | Utility, Management, Decoration |
| Icon | Needed: 400×400 PNG (none yet) |

## Description (Markdown, both sites)

```markdown
# City Builder for MineColonies

**Plan and build your MineColonies town from the sky.**

Press **J** inside your colony and the camera lifts into an overhead, city-builder view. Place huts and decorations,
draw walls and roads with your style pack's real pieces, and run your builders, all through MineColonies' own build
and work-order system. Your character stays where you left it.

> **Beta.** Tested in survival, creative and on dedicated servers, but expect rough edges. Bugs and ideas:
> [issue tracker](https://github.com/VolcanicMG/mccitybuilder/issues).

## Features

**Overhead camera**: pan, zoom, turn and tilt over your colony. It glides over hills and keeps to your colony.

**Place huts and decorations**: pick from your style packs, preview with Structurize's ghost, rotate, mirror and
nudge. Survival places the hut and sends a build request; creative pastes it instantly at any level.

**Walls and roads**: draw segments with your style pack's wall and road pieces. Corners, T-junctions and crossroads
join automatically, loops close with one click, and *Fill gaps* closes the cells whole pieces can't reach.

**Builder queue**: every work order with its builder, stage and progress. Drag to reorder, assign the next builder,
or cancel.

**Manage buildings**: click any building to upgrade, repair, move (creative) or remove it. In survival, Upgrade opens
MineColonies' own window with the materials and builder pick.

**See your colony**: citizen markers show what each worker holds and wears. Hover one to see what's stopping them
working, their status, health, food, happiness factors and job skills. Selecting a hut draws lines to its workers.

**Plan mode**: lay out a whole district first, then build it all at once or piece by piece.

**Built-in help**: a 3D, step-by-step guide for walls and roads, and a controls panel that only shows the keys for
what you're doing.

**Multiplayer**: colony members share built walls and roads, every action is checked by the server against
MineColonies permissions, and admins get settings for survival access.

## Creative and survival

- **Survival** works like playing by hand: you need the hut item, builders do the work, and materials come from your
  colony.
- **Creative** adds instant tools: place, upgrade, repair, move and remove buildings and decorations at once.
  Removing restores the ground that was there before.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1.262+
- MineColonies 1.1.1403+ and Structurize 1.0.832+
- Install on **both client and server**.

## Server settings

In `world/serverconfig/mccitybuilder-server.toml`:

- `plannerRemoteInventories` (off): lets survival players open hut and citizen inventories from the overhead view.
- `strictInventoryReach` (off): closes any hut, rack, grave or citizen inventory a survival player opens from beyond
  reach, including MineColonies' own remote windows.

## Getting started

1. Stand in your colony and press **J**.
2. Pick a hut on the left, move it, click to place.
3. **N** for roads, **B** for walls: click to start, click to drop.
4. The **?** on the road/wall panel opens the guide.
```

## First changelog (1.0.0-beta.1)

```markdown
First public beta.

- Overhead planner view for MineColonies colonies (J).
- Place huts and decorations with style-pack previews; creative instant place, upgrade, repair, move and remove.
- Wall and road tools with automatic joints, loop closing and Fill gaps.
- Builder queue with reordering and assignment.
- Citizen markers with hover cards (blockers, status, needs, happiness, skills) and hut-to-worker lines.
- Plan mode for whole districts.
- 3D step-by-step help and context-aware controls.
- Multiplayer sync of built walls and roads; server settings for survival inventory access.
```

## Gallery to capture (screenshots / GIFs)

1. Hero: a finished colony from above with walls and roads (the page banner).
2. Placing a hut with the ghost preview.
3. Drawing a wall loop that snaps closed and drops.
4. Fill gaps before / after.
5. Builder queue with orders in progress.
6. Citizen hover card (a worker missing a tool).
7. Selected hut with lines to its workers.
8. The 3D help (Closing a loop).
9. Plan mode district before Build all.
