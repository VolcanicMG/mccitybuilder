# Changelog

## 1.0.0-SNAPSHOT

- Fix: citizen markers and builder badges no longer appear where nobody is; they're drawn only at citizens actually
  there (the old fallback used MineColonies' stale last-known position).
- Road / wall drawing shows what you lay: a ghost of the current piece under the cursor before you click, then the
  live segment as real ghost pieces (joints, end caps, gates) with an outline per piece (green, red outside the
  colony or into a building, amber for uncovered cells), a "24 blocks · 3 pieces" label at its middle, and the gap
  or "too short" hint at the cursor. A segment too short for any piece isn't dropped (a hint flashes).
- Line panel: family, level / variant, Towers / Gate / Pieces, a card with the piece being laid (top-down picture,
  name, size, role) and Done / Undo / Build. The per-role overrides moved into the Pieces popup. Gate also applies to
  the next wall segment you draw.
- Fix: corners and junctions no longer overlap. The joint piece's footprint is reserved first and each run ends at its
  face; without a corner piece one run covers the joint square and the others stop at it (straight chains no longer
  share a row). Corner pieces turn the right way for left and right turns (faces matched by block, not only shape).
- Fix: Build all did nothing for a plan of roads / walls (disabled, and its panel sat under the line panel taking its
  clicks). Every Build now says what it builds and why anything stays; panels show "Building i / n".
- Build waits for segments whose pieces are still loading (they could miss pieces or get a butt joint instead of a
  corner); a piece that fails to load is skipped instead of keeping the style "loading" forever.
- Camera: V, zoom-to-cursor or an orbit step next to a tall building no longer snaps the camera up; it glides.
- Camera: turning in place no longer lifts the camera for tall things the view sweeps over; only the ground under it
  and ahead of where it moves count, and the lift holds briefly instead of flickering.
- City Builder opens only while you stand inside a colony you belong to (with hut access); otherwise the action bar
  says why (or that colony data is still loading). Leaving the colony (teleport, pushed onto unclaimed land) closes it (opening from outside used to glitch the camera).
- The palette and road / wall tools start with the colony's own style each time (unless you picked another for that
  colony this session); a missing style falls back with a hint.

- Roads and walls are built from the style pack's own road and wall pieces (no vanilla blocks): straights tiled along
  each segment, corners / T / cross / towers at joints (turned and lined up by matching the pieces' faces), end
  caps, gates; family, level or variant (mixed variants for natural walls), and a piece-set editor per style for
  overrides. Survival: one builder work order per piece; creative: pasted at once. The generated blueprints are gone.
- One combined overlay, always on (the overlay switcher and L are gone): staffing labels and boxes, citizens as
  job-coloured markers, builders as hammer badges with their site, a faint line and what they wait for. Config
  `showCitizens` / `showStaffing` hide parts of it.
- Survival plan Build all queues decorations too: each becomes a builder work order (with City Builder on the server).
- Roads and walls are their own tools now (N / B or the buttons), drawn city-builder style: click a start, a live
  segment follows the cursor (45° snap, Shift = free, snaps to segment ends or onto a segment to branch), each click
  drops a segment and starts the next. Every segment goes into the plan (editable, deletable, conflicts red).
- Renamed: ColonyPlanner is now **City Builder for MineColonies** (mod id `mccitybuilder`, package
  `com.volcanicmg.mccitybuilder`, jar `mccitybuilder-*.jar`, network channels `mccitybuilder:instant_build` /
  `mccitybuilder:instant_road`, config `mccitybuilder-client.toml`, plans under `mccitybuilder/plans/`). The old
  client config and saved plans are picked up automatically; remove the old colonyplanner jar.
- Plan mode (K or the Plan button): drop huts and decorations as a plan, see them as ghosts with their footprints
  and conflicts (overlaps, outside the colony), edit (move, raise, rotate, mirror, level, delete), keep them across
  sessions per world and colony, then Build all / Build selected (creative: pasted one after another; survival:
  huts you have items for, with build requests) or Clear.
- Overlays (picker under the banner, L cycles): Builders shows each builder's site, a line from its hut, a hammer
  badge where the builder is and what it is waiting for; Workers shows staffing per hut (a/b, red / amber boxes)
  and every citizen as a job-coloured marker (hover for name / job / status, click for the citizen window).
- Decorations: the palette lists the style pack's decoration folders ("Deco: ..."), with search; place them like huts.
  Creative pastes them at once; survival opens MineColonies' decoration build window (a builder work order).
- Creative Clean button on a building: leftover placeholder and waypoint blocks become what they stand for.
- Instant creative upgrade / repair cancel the building's queued work order, and work even while one is queued.
- Creative pastes never remove or undermine entities holding items (item frames, armour stands, chest carts).
- The colony border fades into the fog like the terrain.
- Camera: things coming into view ahead lift it smoothly (eased clearance); only the ground right under it can
  snap it up. Instant builds replace beds, signs, banners and other non-storage block entities again (only storage
  and huts are left in place); backslash paths, op-only block data, placements into claimed air and overlapping
  pastes are handled.
- Fix (the real cause of "can't zoom out / zoom into the ground"): the camera's ground came from a heightmap the
  server never sends to clients, so it read about -64. Ground is now the motion-blocking surface minus leaves.
- Camera: no height feedback while orbiting (Q/E, right-drag), the colony and loaded-area bounds apply inside the
  camera (turns and the pivot respect them), the entry height uses the same baseline as the limits, no NaN when
  nothing is loaded yet, the zoom-in limit uses only the ground under the camera, climbs toward obstacles no longer
  stall.
- Instant creative builds: every footprint chunk must belong to the colony; claim and protection mods can refuse
  (a break event per replaced block); block entities and huts are never replaced (empty or not); blocks are only
  skipped when their state matches; place needs PLACE_HUTS and a safe pack path; one paste at a time per player and
  position; command, structure and jigsaw blocks are never pasted.
- The camera never goes into the ground: a hard floor over the camera, just ahead of the view and ahead of its
  movement (leaves count) raises it at once, and zooming in stops 10 above the higher of the land baseline and
  the local surface (hills, towers, cliffs).
- Colony border: about 2.5 screen pixels wide at any distance (at most 0.75 blocks), drawn after the rest of the
  world so it shows while moving too.
- Instant creative builds through a small server part (singleplayer always; a dedicated server needs City Builder
  installed, otherwise the old behaviour stays): Upgrade and Repair paste at once on built huts, and Place pastes the
  chosen level and records that level. The hut block, blocks that already match and any block holding items are
  left alone ("Kept N storage blocks" when one is in the way); placeholders are resolved and waypoints registered.
- Camera height has one reference: a broad, slowly eased terrain baseline under the camera (median over 28 blocks,
  4.5-block deadband, ~1.2 s glide). Zoom limits, the ceiling, the remembered height and terrain follow all use it,
  so full zoom-out is always 160 above the land below, not swinging with where the screen centre lands (ocean,
  mountain). Terrain follow is on by default and smooth (the camera rises over plateaus, trees and buildings don't
  move it). Fixes the endless bobbing, the "can't zoom out as far" regression and the zoom-out swings.
- No more camera jumps: the drawn camera position follows at capped vertical and horizontal speeds (never below the
  intended pan speed), a far or missing turn pivot turns in place, zoom-to-cursor shifts at most twice the height
  change, terrain samples at the edge of the loaded area are ignored, clearance ignores leaves and eases slower.
- Shift + WASD / edge pan moves faster (`fastPanMultiplier`, default 3), easing in and out.
- New crisp 16x16 pixel icons for the placement buttons (place, rotate, mirror, raise, lower, cancel).
- Select a building by clicking or hovering any part of it, not just the hut block (the smallest building wins when
  they nest); its whole outline is drawn, subtle on hover and full when selected. Open works however buried the hut.
- Creative paste is now the build tool's "pretty" placement: placeholder blocks become proper fill or air and
  waypoints are registered instead of placed, and the hut is registered as a colony building (the old paste left an
  unregistered hut block). MineColonies records any pasted hut as level 1.
- Creative Upgrade pastes level 1 only on an unbuilt hut; built huts get a normal work order (pasting over them would
  reset their level and spill the hut's inventory).
- Hover stays steady while the camera moves: it is re-picked every frame, survives a few missed frames, and the pick
  reaches 1024 blocks.
- Zooming out no longer hides buildings and outlines: terrain fog is pushed out by the camera's height, and the
  zoom ceiling is held under what vanilla still renders below the camera (160 blocks, less at render distances
  under 11).
- The camera height is remembered between planner sessions (until you quit the game).
- Turning moved from middle-drag to right-drag; a right-click without dragging still backs out / deselects.
- Creative palette: "All huts" is the default category, so every hut shows at once.
- Creative palette: every hut, with no item or research gate (huts the style pack lacks are greyed), and a level
  picker for the paste. Survival is unchanged.
- WASD and Q/E (and edge pan with middle-drag) work together: the turn pivot moves with the pan.
- Placing is now two-step, like the build tool: click drops the ghost, then adjust it with the arrow keys (Shift = 5),
  by dragging, Shift+scroll / PgUp/PgDn / ▲▼ for height (shown as +2 / ground / -1; G back to ground), R/M, and
  place with Enter or the ✓ button on a floating action bar under the ghost. Esc / right-click go back one step.
- Camera is now an RTS "drone": you set the height (scroll moves only up/down, at least 10 blocks above the ground),
  WASD moves at a speed that scales with height and stops at once, Q/E and middle-drag turn around the point at
  the screen centre, V toggles straight down; terrain only pushes the camera up while it would be too close.
  Options: `minHeight`, `turnSpeed`, `zoomToCursor`, `followTerrain` (now off by default).
- Palette: search box, recently placed row, research-locked huts greyed with their requirements, the selected
  hut's level-1 material list against your inventory (green / yellow / red).
- Placement: the whole footprint is checked against the colony's claims (the server's rule); invalid spots show a
  red box over the footprint and the reason at the cursor, valid ones a green outline.
- Colony border drawn on the ground; the camera stays within the claims (+32 blocks) and the loaded area.
- Queue: icons per work-order type (incl. decorations), a stage progress bar, the builder's name.
- Buildings: hover highlight, workers count, Upgrade/Repair disabled (with the reason) at max level or while a work
  order is queued.
- "Planner · colony" banner, sounds on place and actions, right-click cancels and deselects, Esc clears the search
  first.
- Fix: crash when a hut blueprint loaded (the preview's ground offset was set before it had a position). The ghost
  is only shown once the blueprint is loaded and the cursor is on the ground; any planner error now closes planner
  view cleanly instead of crashing.
- Smooth camera height: follows a terrain average sampled a few times a second (trees and towers ignored) with
  eased height and zoom; option `followTerrain` keeps the entry height instead.
- Planner view: overhead camera over the colony (WASD / edge pan, wheel zoom, Q/E and middle-drag turn), exits on
  J / Esc, death, damage, dimension change and disconnect.
- Hut palette with style pack and category pickers and inventory counts; Structurize ghost preview following the
  cursor with rotate / mirror / height; placement through Structurize's build tool message and MineColonies'
  survival handler, followed by a build request.
- Builder queue with drag-to-reorder priorities, assign to builder, cancel.
- Building selection with Upgrade / Repair / Open hut window.
- Controls guide.
