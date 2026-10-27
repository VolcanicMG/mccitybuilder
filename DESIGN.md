# City Builder for MineColonies design (mod id `mccitybuilder`, formerly ColonyPlanner)

An overhead, RTS-style planner for MineColonies (NeoForge 1.21.1, MineColonies 1.1.1403, Structurize 1.0.832).
It reuses MineColonies' and Structurize's own placement and work-order machinery; it adds no server logic.

## Phase 0 spike: findings (javap on the pack jars)

### Hut placement (Structurize build tool → MineColonies survival handler)

- Client sends `com.ldtteam.structurize.network.messages.BuildToolPlacementMessage(HandlerType, handlerId,
  structurePackId, blueprintPath, BlockPos pos, RotationMirror)` (`sendToServer()` from ldtteam-common's
  `IServerboundDistributor`). The build tool window (`WindowExtendedBuildTool`) builds it with
  `HandlerType.Survival`, the handler id and `blueprintPath = packMeta.getSubPath(blueprint.getFilePath().resolve(
  blueprint.getFileName() + ".blueprint"))`.
- Server: `onExecute` → `BlueprintPlacementHandling.handlePlacement`: if the server lacks the pack it asks the client
  for the blueprint (`ClientBlueprintRequestMessage`), else loads it through `StructurePacks.getBlueprintFuture` and
  `ServerFutureProcessor`, then for Survival calls `SurvivalBlueprintHandlers.getHandler(handlerId).handle(...)`.
  **No item-in-hand, reach, distance or line-of-sight check in Structurize.**
- MineColonies registers `com.minecolonies.core.placementhandlers.main.SurvivalHandler` with id `"minecolonies"`.
  Its `handle` checks, in order:
  1. client-only (custom) packs refused (`structures.nocustomhuts`);
  2. `IColonyManager.getClosestColony(level, pos)` and `colony.isCoordInColony(level, pos)`;
  3. `colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS)` (`placement.noperm`);
  4. town hall: `isFarEnoughFromColonies`; other huts: the whole blueprint inside the colony (`isBlueprintInColony`);
  5. the hut block in the player's inventory unless creative (`InventoryUtils.findFirstSlotInItemHandlerWith`), and
     the item's `ColonyId` component matching the colony (`buildtool.wrongcolony`);
  6. places the hut block, `AbstractBlockHut.onBlockPlacedByBuildTool`, posts `BlockEvent.EntityPlaceEvent`,
     removes one hut item, sets the building's pack / blueprint path / rotation, places construction tape.
  It does **not** create a work order: the hut stands at level 0.
- **Result: nothing blocks placing from a detached camera.** The player's position is only used for sounds and
  messages. No server-side workaround (and no server half of this mod) is needed.

### Build requests and the work-order queue (MineColonies)

- `BuildRequestMessage(IBuildingView, Mode BUILD | REPAIR | REMOVE, BlockPos builder)`: BUILD calls
  `IBuilding.requestUpgrade(player, builder)` (level 0 → 1 is the initial build), REPAIR `requestRepair`, REMOVE
  `requestRemoval`; with a pending work order it removes it instead. `BlockPos.ZERO` = no particular builder.
  It extends `AbstractBuildingServerMessage` → `AbstractColonyServerMessage`: colony by id + dimension, the
  message's `permissionNeeded()` checked against the colony permissions, optional owner-only. No distance check.
- `WorkOrderChangeMessage(IBuildingView anyBuildingOfTheColony, int workOrderId, boolean remove, int priority)`:
  remove → `WorkManager.removeWorkOrder`, else `setPriority`. `WorkManager` hands out work orders by priority
  descending (`Comparator.comparing(getPriority).reversed().thenComparing(WORK_ORDER_COMPARATOR)`).
- `BuilderSelectWorkOrderMessage(builderHutView, workOrderId)`: the builder claims that work order.
- Client data: `IColonyManager.getClosestColonyView / getColonyView(id, dim)`, `IColonyView.getWorkOrders()`
  (`IWorkOrderView`: id, priority, type BUILD/UPGRADE/REPAIR/REMOVE, current/target level, display name,
  `isClaimed`, `getClaimedBy` = builder hut pos, `getStage`), `getClientBuildingManager().getBuildings()/getTownHall()`,
  builder huts are `BuildingBuilder.View` (`getWorkerName`).
- Hut window: `IBuildingView.openGui(boolean)` opens the BlockUI window client-side.

### Blueprint preview

- `RenderingCache.queue(String key, BlueprintPreviewData)`: Structurize renders every queued preview in the world
  from the current camera. `BlueprintPreviewData(false)` (no server sync), `setBlueprint`, `setRotationMirror`
  (rotates the blueprint), `setPos`, `setGroundOffset`. The build tool uses key `"blueprint"`; the planner uses
  `"mccitybuilder"` so both coexist.
- Hut blueprints: `StructurePacks.findBlueprintFuture(pack, predicate, registries)`; the planner takes the level-1
  blueprint whose primary (anchor) block is the hut (`getFileName().endsWith("1")`), cached per pack and hut.
- Ground: the build tool's default ground style sets `groundOffset = getGroundAnchorOffset(bp, 1) - 1`, and
  `setGroundOffset` *shifts the existing pos* (`pos = pos.below(old).above(new)`, so it NPEs with no pos). The
  planner never calls it: its anchor is the looked-at block raised by `getGroundAnchorOffset(bp, 1) - 1` (PgUp/PgDn
  adjust), set with `setPos`. Order: `setBlueprint` → `setRotationMirror` → `setPos`, and nothing is queued until
  the blueprint is loaded and the cursor hits the ground (`getBlueprint()` also returns null while pos is null).
- Fail-safe: every planner entry point (tick, frame, world render, screen handlers) runs through `Planner.guard`;
  an exception is logged once, our preview is removed and planner view exits (camera, HUD, hand restored).

### Camera

- Least invasive, no mixin: a client-only `Marker` entity set with `Minecraft.setCameraEntity`, moved every frame
  (`RenderFrameEvent.Pre`, old position = new so no interpolation lag). The player is not the camera entity, so its
  body renders where it stands; movement input stops because a screen is open (`setScreen` releases all keys).
  Chunk culling and sections follow the camera; the pan target is kept within the player's render distance.
  `RenderHandEvent` and `RenderGuiLayerEvent.Pre` are cancelled while planning.
- One height reference: `TerrainFollow.baseline()`, the median surface (no leaves) on a 4-block grid within 28 blocks
  of the camera's x/z, sampled at 4 Hz, moved only when the sample differs by more than 4.5 blocks and eased with a
  1.2 s time constant. It depends neither on the camera's height nor on where the centre ray lands (the old
  centre-hit reference fed back into the height: endless bobbing). Scroll limits (min / 160), the render-distance
  ceiling (`limitCeiling`, only when it binds, 2-block deadband), the remembered height and the fog / border
  "height above ground" all use it; terrain follow (default on) shifts the altitude by its per-frame change. The
  centre hit is only for zoom-to-cursor, the turn pivot and pan speed.
- Column heights come from `planner/Columns`: clients only receive the WORLD_SURFACE and MOTION_BLOCKING heightmaps
  (`Heightmap.Types.sendToClient`); a client chunk's MOTION_BLOCKING_NO_LEAVES map exists but stays empty and reads
  minBuildHeight. "Ground" is therefore MOTION_BLOCKING walked down past leaves and non-solid, fluid-free blocks
  (at most 40). Using NO_LEAVES made the baseline about -64 in a real game: the zoom-out cap sat ~30 above the land
  and the zoom-in floor underground.
- The per-frame loop is `planner/CameraRig` (pure, driven by a `World` of column heights): baseline sampling at 4 Hz,
  terrain follow, ceiling, then `DroneCamera.step` with two surface inputs (leaves included): the **clearance**
  obstacle (within 2 blocks of the drawn and target position, 4 and 8 blocks ahead along the view, and ahead along
  the pan velocity by one frame + 1.5 s + 4 blocks), which lifts the camera through the eased clearance and the speed
  cap; and the **hard floor** (within 2 blocks of the drawn and target position only), applied last as an instant
  raise to floor + 3. Things coming into view therefore lift the camera smoothly; only ground right under it can
  snap it. The vertical cap is max(16, 3 x height over the obstacle, 8 x the remaining gap) blocks/s. While a turn
  holds terrain follow back, the ceiling and the saved height use the baseline minus the held change; yaw-only
  turns keep the current distance to the pivot. The zoom minimum is `max(baseline, last floor) + minHeight`. SelfCheck runs
  the rig end to end (open, zoom fully out / in, flat / hill / tower / trees, panning into a 60-block wall).
- No jumps: `DroneCamera.y()` / `camX()` / `camZ()` (what is drawn and picked from) follow the targets at capped
  speeds (vertical max(16, 3 x height above local terrain) b/s; horizontal max(32, 6 x height, 1.5 x pan velocity)),
  so clamps, turns and any remaining discontinuity glide. A pivot farther than 4 x its height below the camera turns
  in place; zoom-to-cursor shifts at most 2 x the altitude change; a terrain sample with under half its columns
  loaded keeps the previous value; clearance (3 blocks above the highest non-leaf column within 3) eases at 0.2 s.
  Shift multiplies the pan speed (`fastPanMultiplier`).
- Drone camera (`planner/DroneCamera`, pure): position + yaw + pitch; the user owns the altitude. Scroll changes only
  the altitude (11% of the height above the terrain baseline per notch, ≥ 1 block, clamped to
  ground + `minHeight` (10) .. ground + 160, eased ~0.12 s; the clamp is on the target, so it glides into the limit).
  WASD / edge pan move horizontally relative to yaw at `panSpeed` per 20 blocks of height, ≤ 0.1 s acceleration,
  immediate stop. Q/E (`turnSpeed`, 90°/s) and right-drag (a right press that moves ≥ 4 px; a shorter one is the back-out click, on release) turn and tilt around the ground point at the screen centre
  (ray hit when the turn starts; in place with no hit): the camera keeps its height and swings to the horizontal
  distance the pitch needs, so that point stays centred. Terrain never changes the altitude; a clearance offset
  (eased ~0.15 s) lifts the camera 3 blocks above the highest surface within 3 blocks while it is needed.
  `followTerrain` (opt-in) carries the altitude with the smoothed ground. Bounds: the player's loaded area and the
  colony's claimed chunks + 32 blocks. Entry: 30 blocks above the ground at the player, 60° down, player's yaw.
- Ray picking: the ray through the mouse from the camera's yaw / pitch, the options FOV (vertical) and the window
  aspect (`planner/CameraMath.ray`), then `Level.clip` from the marker's position.

### Creative placement (decompiled, Structurize 1.0.832 / MineColonies 1.1.1403)

- The build tool sends `BuildToolPlacementMessage` with `HandlerType.Complete` (paste) or `Pretty` (paste with
  "pretty" placement) for creative players; `Survival` otherwise (`WindowExtendedBuildTool.handlePlacement`).
- Server: `BlueprintPlacementHandling.process` has **no creative / permission check of its own** for Complete or
  Pretty. For a hut anchor (`ISpecialCreativeHandlerAnchorBlock`) it calls `AbstractBlockHut.setup(...)`, then pastes
  through `CreativeBuildingStructureHandler` (`fancyPlacement` = Pretty).
- **Pretty is what the planner sends** (place, and "build now" on an unbuilt hut). Fancy placement resolves the
  markers: `SubstitutionPlacementHandler` leaves the world block, solid substitution → `getSolidBlockForPos` fill,
  fluid substitution → the dimension's fluid, tag substitution → its replacement block, MineColonies'
  `SolidPlaceholderPlacementHandler` → its replacement, and `WayPointBlockPlacementHandler` removes the block and
  registers a colony waypoint instead.
- Pretty `setup` (creative or not): `canPaste` (colony at the anchor or a town hall, `Action.PLACE_HUTS`, `canPlaceAt`)
  unless `blueprintBuildMode`; `destroyBlock(pos, true)` (drops whatever is there, incl. an existing hut and its
  inventory), places the hut, `onBlockPlacedByBuildTool` → `setPlacedBy` → `addNewBuilding` (building registered),
  sets pack / path / rotation and the level parsed from the path. **The parse reads the second-to-last character**
  (`substring(len - 2, len - 1)` of `baker3`) → `NumberFormatException` → **level 1 for any blueprint level**. The
  paste then skips the anchor (`HutPlacementHandler.doesWorldStateMatchBlueprintState` = same state), so the hut's
  schematic data from the blueprint is never applied and nothing raises the level afterwards.
- Complete + creative: `setup` returns at once; `HutPlacementHandler` calls `setPlacedBy` only for fancy placement, so
  a Complete paste of a new hut places the block **without registering a building**, and placeholders / waypoint
  blocks stay. Over an existing hut the anchor is skipped and the level is not changed.
- Without the server part, repair in creative stays a work order for the same reason, and a Pretty paste would not clean old placeholders
  anyway: the substitution handlers count a world block equal to the blueprint's placeholder as already placed.
- Without the server part (see Instant creative builds), upgrade in creative: a built hut gets a normal work order (Pretty would reset it to level 1 and spill its inventory;
  Complete leaves placeholders and the old level). An unbuilt (level 0) hut gets a Pretty paste of its level-1
  blueprint.
- The planner pastes only when the client player is in creative (the gate the build tool's UI applies). The
  server-side gaps above are MineColonies'/Structurize's, not opened further by the planner.

### Instant creative builds (server part, `server/InstantBuild`)

- An optional play-to-server payload (`mccitybuilder:instant_build`, registered `optional()`): clients and servers
  without the mod still connect; the client uses it only when `hasChannel` says the server has it and the player is
  in creative, else it falls back (Pretty paste for placing, work orders for built huts).
- Re-checked again on the server thread once the blueprint has loaded (player still creative and permitted, the
  building still absent / at the expected level). One paste per player and per position at a time (stale after
  5 min); per-player state is dropped on logout. Every chunk under the footprint must be loaded and owned by the
  colony (`ColonyUtils.getOwningColony`, MineColonies' own rule), for all modes. Before anything changes, a NeoForge
  `BlockEvent.BreakEvent` is posted for every block that will be replaced and one `EntityPlaceEvent` per chunk that
  gets blocks placed into air; any cancellation (claims, protection mods) aborts the whole paste. Side effects,
  accepted for a creative-only tool: listeners that react to breaks (quests, stats, vein mining) see thousands of
  synthetic breaks, and the events fire when the paste is queued, a few ticks before the blocks change. Running
  pastes lock their footprint chunks, so overlapping pastes can't interleave; a hut placed for a building that then
  fails to register is put back. Block entity data that only operators may set (signs, spawners) is dropped unless
  the player may use game-master blocks. Place paths are normalised (`\` to `/`) first, then must be relative, without `..` or `:`, not `scans/`, resolve
  inside the pack and carry the requested level digit; packs under the server's `blueprints/clients/` (client-synced)
  are refused by absolute path. Command,
  structure and jigsaw blocks are never pasted. Place requires `PLACE_HUTS` as well, a replaceable block at the
  anchor and a level within the hut's max, all checked before the hut is placed.
- Server checks, all re-done there: creative, a 1 s per-player rate limit, a loaded position, a colony there with
  `MANAGE_HUTS`; upgrade = current + 1 <= max, repair = current >= 1, place = no building there yet, a hut anchor,
  the footprint corners in the colony and `canPlaceAt`. Rejections are logged, nothing is sent back.
- Paste: the target level's blueprint (the building's own pack / path / rotation for upgrade and repair), rotated;
  every position where `PasteRules.decide` says SKIP (the hut block, or the world already has exactly that block state) or
  KEEP (storage: a `Container` block entity or an item handler on any side, empty or not, or a hut block;
  other block entities such as beds, signs and banners are replaced) becomes Structurize's
  substitution block, which fancy placement leaves untouched; the primary offset is restored afterwards.
  `PlaceStructureOperation` with a `CreativeStructureHandler` (fancy) subclass whose `onCompletion` finishes.
- Finish = `AbstractWorkOrder.onCompleted`: the blueprint's `blueprintDataProvider` tile data goes to the hut's block
  entity (`readSchematicDataFromNBT` -> `onUpgradeSchematicTo` -> `upgradeBuildingLevelToSchematicData`: corners,
  level, `onUpgradeComplete`); if the hut has no such data, the builder's fallback `onUpgradeComplete` +
  `setBuildingLevel`. Then every block in the footprint goes through `IBuilding.registerBlockPosition` (racks, beds,
  module blocks), as the builder's `BuildingStructureHandler.triggerSuccess` does.
- Place first puts the hut block and calls `onBlockPlacedByBuildTool` (registers the building at level 0), then the
  same paste and finish take it to the chosen level.

### Decorations, cleaning, entities (phase A)

- Palette: after the hut categories come "Deco: <folder>" categories, one per folder of the style pack holding
  blueprints whose anchor is not a hut (folders found by walking the pack, each folder's blueprints loaded with
  `StructurePacks.getBlueprintsFuture` in the background; a folder appears once loaded and non-empty). A scrollable
  name list (wheel over the palette); search filters it. Ghost, rotate, mirror, height and materials as for huts.
- Creative: the server part's DECO mode (same checks as PLACE: path, pack, footprint chunks, events, storage and huts
  kept; the anchor must not be a hut; no building is registered), else Structurize's own Pretty paste. Survival
  (verified in `SurvivalHandler`): a non-hut anchor makes MineColonies send `OpenDecoBuildWindowMessage`, so the
  planner sends the normal Survival placement and MineColonies' decoration build window opens (builder work order).
- Instant creative upgrade / repair remove the building's queued work orders (`WorkManager.removeWorkOrder(id)`,
  what `WorkOrderChangeMessage` does) when the paste is queued.
- Entities: Structurize's placer only spawns blueprint entities (deduplicated) and never removes any (verified in
  `StructurePlacer`). Entities holding items in the footprint (filled item frames, armour stands with gear,
  container entities, anything with an item handler) keep the blocks they occupy, the block under them and, for
  hanging entities, the block they hang on (KEEP).
- CLEAN (creative, server part): Structurize substitution / solid / fluid / tag substitution blocks and MineColonies
  waypoint blocks in the building's current-level footprint become the blueprint's block there, or, where the
  blueprint itself has a placeholder, the fill (`BlockUtils.getSubstitutionBlockAtWorld`), the dimension's fluid or
  air; waypoints are registered with the colony. Same permission, footprint, rate-limit and break-event checks.
- Border fog: drawn with a fog-less shader after the level, so each vertex is fogged on the CPU with the terrain fog
  the planner set (cylindrical distance, linear mix to the fog colour). The bright pass still shows through water on
  Fabulous (the composite keeps water depth in another target); not fixed.

### Overlay (`client/OverlayView`, `planner/Overlays`)

- One combined overlay, always on (no switcher); client config `showCitizens` / `showStaffing` (default on) hide
  parts of it. Rebuilt from the client colony view at most 4 times a second; positions from the citizen entity when
  loaded, else `ICitizenDataView.getPosition()`.
- Staffing: per hut, the `WorkerBuildingModuleView`s' assigned citizens vs `getMaxInhabitants()` as an "a/b" label
  (red none, amber under, green full; a label overlapping one already drawn is skipped, labels fade above 90 blocks)
  and red / amber boxes around unstaffed / under-staffed huts.
- Citizens: a marker in a stable colour per job id; hover = name, job, "needs help" (blocking interactions); click =
  MineColonies' `MainWindowCitizen`.
- Builders: instead of a citizen marker, a hammer badge (drawn last, above the dots, and hit-tested first); their
  claimed work order's site boxed in cyan with a faint line from the hut; the badge tooltip lists up to 8 waiting
  materials from the hut's `BuildingResourcesModuleView` (DONT_HAVE / NEED_MORE / IN_DELIVERY, amount - available);
  clicking it selects the builder's hut.

### Plan mode (phase C, `client/PlanView`, `planner/Plan`)

- K or the "Plan" button toggles it. While on, confirming a ghost (Enter / the check button) adds a plan entry
  instead of building: hut id (or none for a decoration), pack, blueprint path at the chosen level, anchor,
  RotationMirror and the footprint box. Entries render as Structurize previews (one RenderingCache key each, our own
  rotated blueprint copy per pack / path / rotation) with outlines: green, red with a conflict, white when selected.
- Click inside a planned footprint selects the entry (before buildings). Arrows (camera-relative, Shift = 5),
  PgUp / PgDn, R, M, [ ] (hut level within the pack's files) and Delete edit it; the box follows rotations and levels
  once the rotated blueprint is known.
- Conflicts (pure `Plan.conflicts`): outside the claims (every footprint chunk, MineColonies' rule), overlapping an
  existing building's box, overlapping another entry.
- Saved on every change to `mccitybuilder/plans/<sp_world or mp_server>/<dimension>_<colonyId>.txt` under the game
  directory: a header line and one tab-separated line per entry; damaged lines are skipped.
- Build all / Build selected: outside-the-colony entries stay. Creative: a client queue sends one request at a time
  (server part PLACE / DECO, next once the hut's building view appears or after 15 s / 3 s; without the server part
  Structurize Pretty pastes 1.5 s apart). Survival: huts with an item in the inventory and no research lock are
  placed at level 1 with MineColonies' survival handler and get their build requests when the building views appear;
  the rest stay in the plan. Decorations stay in survival (MineColonies opens one decoration window per placement).
  The queue only runs while planner view is open.
- Survival decorations in Build all (server part): the server part's DECO_ORDER mode does what MineColonies'
  `DecorationBuildRequestMessage` does (MANAGE_HUTS; `WorkOrderDecoration.create(BUILD, pack, path, name, pos, rot,
  0)`, `setBlueprint`, `addWorkOrder`), after the same checks as DECO (pack path rules, client packs refused, the
  anchor is not a hut, every footprint chunk owned by the colony) and refusing a location that already has a work
  order (MineColonies' message would toggle it off). It doesn't need creative. Without the server part decorations
  stay in the plan (MineColonies' window, one by one).

### Roads and walls from the style's own pieces (`client/LineView`, `client/PieceCatalog`, `planner/PieceSets`, `planner/PieceLayout`)

- Own tools (N = road, B = wall, or the buttons beside Plan); hut / decoration placement is unchanged. Chains are
  drawn city-builder style (`LineSnap`): click a start (a planned segment's end, or onto a segment, which splits it
  there so the branch starts at a joint), a live segment follows the cursor along 0 / 90° (the packs' pieces are
  straight runs; no diagonals in v1), each click drops a segment into the plan and starts the next at its end.
  Right-click / Esc ends the chain (again: leaves the tool), Backspace takes back the chain's last segment.
- Pieces (verified against MineColonies 1.1.1403's 22 bundled packs): road pieces live under
  `infrastructure/roads/**`, wall pieces under `walls/**`. `PieceSets` sorts them: families = folder (role-named
  sub-folders like `walls/corner`, `walls/gate`, `roads/straight` fold into their parent), split by a style prefix
  (`cobble_`, `fancy_`, `plain_`; not role words like `long_`); roles from name keywords (gate / drawbridge /
  barbican; inner corner; T: tjunction / tcorner / junction; cross / intersection; corner / curve / outer; tower /
  pillar; stairs / climb / step / incline = slope; end; straight / segment / wall / long / short / path / section);
  numbered groups are levels when their footprints differ (medieval `segment1..5`) and variants when equal
  (fortress `wall1..4`, nordic `loooong1..3`). A family needs a straight to be offered. Packs without road / wall
  folders show "this style has no roads / walls".
- Layout (`PieceCatalog.layout`, math in `PieceLayout`): per segment, joints where 2+ lines of the same family meet
  (two in a straight line: none; else corner / T / cross by count, or a tower when the line asks) are placed once,
  centred on the joint cell, turned to the quarter turn whose faces toward the lines best match a straight's end
  face (solid-cell profiles, Jaccard over lateral shifts within 3), and the straights are shifted across the line by
  the best profile match at their start joint. Free ends get an end cap when the family has one; a gate (selected
  wall segment, G) goes in the middle. Straights fill the rest: lengths of the same width as the primary straight,
  least left over then fewest pieces (coin change); what can't be covered is reported as "short". Each piece sits on
  the ground at its own centre (stepped; slope pieces are not used in v1), anchored like the hut ghost (top ground
  block + the blueprint's ground anchor offset - 1). Planned pieces show as Structurize ghosts with footprint outlines
  (red: outside the claims or into an existing building).
- Options for the next segments or the selected one: family, level or variant (variants: one, or mixed by piece
  index), towers. The piece-set editor (Pieces) overrides which group plays each role (auto / none / any group of the
  family), saved per pack in `mccitybuilder/piecesets/<pack>.txt`.
- Build: each piece is a normal decoration from the real pack, through the server part as `piece_batch` messages of
  up to 16 pieces in drawing order; the server runs them one by one through the same DECO (creative paste) /
  DECO_ORDER (survival builder work order) checks as single decorations, plus: every path must be a road / wall
  piece, a batch can queue behind a running one (96 queued at most), at most 64 open work orders created by City
  Builder per colony, one summary message per batch. Survival is checked when ordered: builders later replace what
  is there when they build.

### Height, fog and what stays visible

- Vanilla limits: `SectionOcclusionGraph.getRelativeFrom` drops sections more than `renderDistance * 16` blocks above
  or below the camera, and terrain fog uses `FogShape.CYLINDER`, which in `fog.glsl` is `max(length(xz), abs(y))`,
  so fog also ends `renderDistance * 16` blocks straight down. Climbing past that hid the buildings, and the outlines
  too, because lines are fogged as well.
- The planner pushes terrain fog out by the camera's height above the ground (`ViewportEvent.RenderFog`, planner view
  only) and caps the zoom at `min(160, renderDistance * 16 - 16)` above the ground (re-clamped each frame, so a
  lower render distance or a valley pulls the camera down). Without a mixin this is the hard limit: at render
  distance 11+ the whole 10..160 range works, at 8 the ceiling is 112. Loaded chunks are still the server's, around
  the player.
- The height above the ground is remembered for the client session and restored, clamped, when the planner reopens.

### Colony border

An amber ribbon of quads (not GL lines) along the claim edges, in 2-block steps whose endpoints sit 0.2 above the
highest of the four columns at that corner, so it follows the terrain without gaps. Width about
2.5 screen pixels at each vertex's distance from the camera, at most 0.75 blocks. Two passes with custom `RenderType`s: no depth test at 30 % alpha
(seen through hills), then depth-tested at 90 %. Drawn at `AFTER_LEVEL` (model-view pushed by hand), after every
vanilla pass and the Fabulous composite, not inside the translucent chunk pass where the other overlays draw.

### Building selection

- `IBuildingView` carries no corners. The box is the building's current blueprint (`getStructurePack` /
  `getStructurePath`) rotated like the building (`getRotationMirror`), anchored at its hut block: what MineColonies'
  `ColonyBlueprintRenderer` (build goggles) outlines. Loaded once per pack/path/rotation on our own copy and rotated
  on the client thread; the hut block alone while loading. A click or hover picks the smallest box containing the
  hit block (`ColonyGeometry.smallestContaining`), re-picked every frame (reach 1024) and held for 6 frames on a miss. Open uses `IBuildingView.openGui(false)` (client-side window).

### Creative palette

Creative adds an "All huts" category, the default, so every hut is in one grid (survival keeps its categories). It
has no item or research gate; a hut whose lookup found no blueprint in the style pack is
greyed. Picking one shows a level picker (1 .. the highest `<name>N.blueprint` next to the level-1 file); the ghost,
materials and paste use that level's blueprint. Decorations are not offered (they need a pack browser).

### Placement modes

`planner/PlacementState` (pure): FOLLOW (ghost under the cursor, ground-aligned anchor + height offset) → click →
ADJUST (ghost fixed; arrow keys move it 1 / Shift 5 blocks along the camera's dominant axes; left-drag moves it by
whole blocks; Shift+scroll, PgUp/PgDn or ▲▼ change the height offset; G snaps to ground; R/M rotate/mirror) →
Enter / ✓ places; Esc or right-click goes back to FOLLOW, and again ends placing. A floating action bar of vanilla
buttons is projected under the anchor (`CameraMath.project`, the inverse of the picking ray).

### Polish details

- Materials: MineColonies' build window computes them with Structurize's `StructurePlacer` and
  `Operation.GET_RES_REQUIREMENTS` over a `LoadOnlyStructureHandler`, stepping until `FINISHED` and adding each
  step's `getRequiredItems()`. The planner does the same once per blueprint on a fresh copy (the preview's copy is
  rotated) in empty air above the build area so existing blocks don't count.
- Colony border: `IColonyManager.getClaimData(dimension)` (client claim data) → chunks owned by the colony; the
  server's footprint rule (`SurvivalHandler.isBlueprintInColony`: every chunk under the blueprint owned by the colony)
  is checked on the client the same way. Invalid placements get a translucent red box over the footprint and the
  reason at the cursor; valid ones a green outline.
- Research: `AbstractBlockHut.areRequirementsMet / getRequirements(level, pos, player)` grey a hut and list why.
- Queue progress: the client view only has the builder's stage (`IWorkOrder.getStage`), shown as a stage-based bar.
- Workers: `IBuildingView.getAllAssignedCitizens()`; a pending work order (`hasWorkOrder`) disables Upgrade/Repair.

## Packages

| Package | Minecraft? | What |
|---|---|---|
| `planner` | no | `DroneCamera` (the camera model; `step` = turn + move with the pivot carried along), `PlacementState` (follow / adjust), `CameraMath` (ray, pan, ease, clamp), `ColonyGeometry` (claims, footprint, border, bounds, building pick), `Costs` (material list), `QueueOrder` (priorities, stage progress), `QueueIcons`, `HutCatalog` (palette categories, level paths), `Hints` (controls guide) |
| `client` | yes | `Planner` (state, camera, placement, selection), `PlannerScreen` (UI + input), `Blueprints` (hut blueprint lookup), `VanillaUi`, `PlannerClient` (key, events) |

## Flow

Place: palette click → `Planner.startPlacing(hut)` → ghost follows the cursor (`RenderingCache`) → click →
`BuildToolPlacementMessage` (Survival, "minecolonies") → when the hut's building view appears in the colony view
(≤ 15 s) → `BuildRequestMessage(BUILD)` creates the builder work order.

Queue: rows sorted like `WorkManager` (priority desc, id). Drag a row → `QueueOrder.move` gives priorities n..1 for
the new order → one `WorkOrderChangeMessage` per changed work order. "B" asks the next builder hut to take it, "x"
cancels it.

Select: click or hover anywhere inside a building's box (see Building selection) → Upgrade / Repair / Open.

Exits: J / Esc, death, damage (`hurtTime`), dimension change, disconnect. A hut window opened from the planner
returns to the planner when it closes.
