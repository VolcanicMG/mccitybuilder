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
  obstacle (within 2 blocks of the drawn and target position, and every 4 blocks ahead along the pan velocity up to
  one frame + 1.5 s + 4 blocks, at most 64; nothing ahead while not panning, so turning in place never reacts to
  what the view sweeps across), which lifts the camera through the eased clearance (held 0.6 s before it releases,
  so it doesn't flicker) and the speed cap; and the **hard floor** (within 2 blocks of the drawn and target position only), applied last as an instant
  raise to floor + 3. The hard floor is the drawn position's footprint only; the target's footprint (and the way the
  drawn camera still has to glide to it) feeds the eased clearance, half a block high so the climb really reaches
  it, and the drawn x / z is held back while the next step would be over something it can't clear yet. A target jump
  (V, zoom-to-cursor, an orbit step) beside a tall building therefore glides up instead of snapping ~13 blocks.
  Things coming into view lift the camera smoothly; only ground right under it can snap it. The vertical cap is max(16, 3 x height over the obstacle, 8 x the remaining gap) blocks/s. While a turn
  holds terrain follow back, the ceiling and the saved height use the baseline minus the held change; yaw-only
  turns keep the current distance to the pivot. The zoom minimum is `max(baseline, last floor) + minHeight`. SelfCheck runs
  the rig end to end (open, zoom fully out / in, flat / hill / tower / trees, panning into a 60-block wall).
- No jumps: `DroneCamera.y()` / `camX()` / `camZ()` (what is drawn and picked from) follow the targets at capped
  speeds (vertical max(16, 3 x height above local terrain) b/s; horizontal max(32, 6 x height, 1.5 x pan velocity)),
  so clamps, turns and any remaining discontinuity glide. A pivot farther than 4 x its height below the camera turns
  in place; zoom-to-cursor shifts at most 2 x the altitude change; a terrain sample with under half its columns
  loaded keeps the previous value; clearance (3 blocks above the highest non-leaf column within 3) eases at 0.2 s.
  Shift multiplies the pan speed (`fastPanMultiplier`).
- Placing a hut / decoration: the pick ray stops on tall grass, flowers and snow layers (OUTLINE clip), so the ground
  under the cursor is the hit block settled down past blocks that aren't ground (`Columns.settle`, the same rule the
  line tools' column heights use); the ghost then stands on the soil, not one up on the grass.
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

### Moving and removing (server part, protocol 4)

- `Request` carries a source position and rotation (`from`, `fromRot`); `PieceBatch` a mode instead of a survival
  flag. Modes: MOVE (a building), DECO_DELETE, DECO_MOVE, DECO_REMOVE_ORDER (survival).
- MOVE: creative, MANAGE_HUTS + PLACE_HUTS, the source building exists at its level (>= 1), is not the town hall and
  has no parent or children, the target has no building, both in the same colony; the source's own pack / blueprint
  at its level; then every PLACE check at the target (hut anchor, replaceable, `canPlaceAt`, level within max,
  footprint chunks loaded + owned, break / place events all or nothing) and the source footprint's chunks loaded,
  owned and locked with the target's; both positions busy-marked. The new building is placed and pasted exactly like
  PLACE (abort = nothing lost: the old building is untouched until the paste completed), then `moveFinish`: every
  item handler in the old footprint (the hut's inventory included) is emptied into the handler at the same
  blueprint-relative position of the new one (`newPos = pos + (oldRot.calcDifferenceTowards(newRot)).applyToPos(old -
  from)`; the rotation difference is checked with `add`, else inverted), leftovers go to the new hut's inventory, then
  the player's inventory, then are dropped at the player; each `IAssignsCitizen` module of the old building hands its
  citizens to the module of the same class in the new one; the custom name is copied; `destroy()`, the old hut block
  removed, then `delete` over the old footprint. Module settings are not carried (said in the chat summary).
- `delete` (DECO_DELETE, and a move's retirement): only blocks that still equal the blueprint's state go
  (`PasteRules.remove`): never a hut, storage that still holds items, or an item-holding entity's support; an emptied
  container goes; one BreakEvent per block before anything changes, all or nothing; removed top down.
- DECO_MOVE: the DECO paste at the target, then the container copy and the matching delete of the old (from +
  fromRot). DECO_REMOVE_ORDER: MineColonies' `WorkOrderDecoration` with REMOVE (the builder removes whatever stands
  at the blueprint's solid positions), MANAGE_HUTS, owned chunks, one order per location, the 64 open-order cap.
- Client: a Move button on a built building (creative + server part; off for the town hall and parent / child
  buildings): the hut goes out as a ghost at the building's level, placed like a new hut (rotate, mirror, nudge),
  confirm sends MOVE. Removing a built road / wall record (Del / Remove) also takes its stored pieces down: creative
  DECO_DELETE batches, survival DECO_REMOVE_ORDER batches with the warning. Decorations placed from the palette
  leave the plan when built, so they have no record to move or delete from the planner (the server modes are there
  for a later entry point; decoration controllers aren't read on the client).

- Only what City Builder built: `server/BuiltRecords` (per-dimension `SavedData`) records every decoration / piece
  placed (DECO completion, DECO_MOVE's new spot) or ordered (DECO_ORDER) as colony | pack | path | anchor | turn
  (`PasteRules.recordKey`). DECO_DELETE, DECO_MOVE and DECO_REMOVE_ORDER need an exact record (removed on success or
  when the removal order is placed), refuse a footprint that overlaps any building's corners, a hut block or storage
  with contents (`touchesOthers`), a removal order needs the decoration mostly standing (half its solid blocks), and
  removal orders count toward the 64-order cap. A move's source and target footprints may not overlap
  (`PasteRules.overlap`); the one loaded blueprint is turned to the source's rotation for its footprint and the
  clean-up, to the target's for the paste. A claim refusing the clean-up after a move leaves the move done and says
  "old site partly kept". The client has no list of those records yet (palette decorations still aren't selectable
  for move / delete; a sync on planner open would give that).
- Built segments freeze the pieces their accepted batches actually sent (`PlanView.batchSent`), never a later layout.

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

- Citizen markers and builder badges are drawn only at a live citizen entity on the client (`Overlays.showCitizen`;
  the entity must be a citizen entity with this citizen's id, alive). Verified in 1.1.1403: `ICitizenDataView
  .getPosition()` is `CitizenData.lastPosition`, set by the server while the entity ticks but only sent with a
  citizen view update, so it lags (indoors, asleep, moved, despawned, dead); markers from it appeared in empty
  fields. No "last known" marker beyond tracking range either: the camera stays inside the loaded area, and that
  position can't be trusted. Builder sites (from the work order) still show without the badge.

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
- Single entries through the server part (creative huts and decorations, survival decorations) go one at a time
  through `planner/BuildQueue` and stay in the plan until the server answers (`batch_result` with the request's id,
  protocol "3"; the server answers with the request's own id, also late and also after an exception, which counts as
  refused): accepted once the paste finished (or the work order was made), "busy" (another paste / batch /
  rate limit: sent again after 1.5 s, up to 40 times) or "refused" (stays; the server log says why); no answer in
  2 minutes: stays (a late yes still moves it to built, so it isn't sent twice). The run ends with "Built n / m" and each entry that stayed. (Before, the next request went out
  when the previous hut's building view appeared, while its paste still ran: the server refused it as busy and the
  entry was already gone from the plan, so only one of two huts was built.)
- Build all / Build selected cover structures and road / wall segments (`Plan.decide` / `decideLine`, pure). Every
  Build answers in chat and as a toast: "Building N structures and M segments", or "Nothing to build", plus each
  reason something stayed (outside or into a building, no hut item / research, decorations or segments needing
  the server part, segments still loading or without pieces, or already sending). The plan and line panels show "Building i / n".
  The plan panel stacks above the line panel (it used to sit under it and take its clicks, and Build all was
  disabled for a plan of segments only, the "Build all does nothing" bug). Outside-the-colony entries stay. Creative: a client queue sends one request at a time
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
- Joints, reserved before the runs (`PieceLayout.reach / square / owner / offset`, SelfCheck covers L both ways, T,
  cross and a straight chain): with a joint piece every run starts right past its face. Without one (e.g. spacewars
  roads have no corner) it is a butt joint: the square where the lines' bands cross is covered by one line (one with
  a line straight opposite, so a T's bar runs through, else the first drawn), which reaches back across it; the
  others stop at its face. Straight chains therefore no longer share their joint row. Cells no piece fits go to a free
  end (`runStart`), so runs always meet their joints; they are drawn as an amber box. With joints at both ends there is
  no free end: the gap stays beside the second joint (by design; it is reported as "short" and drawn). The joint's quarter turn scores
  each face toward a line against a straight's end face by shape and by block (a road corner's open and closed sides
  can share a shape; the open ones share the road's blocks), and every turn must be loaded before one is picked.
  The layout reports completeness per line: a line whose pieces or joint piece were still loading (a missing piece,
  or a butt joint standing in for a corner / T / cross) is never built (`Plan.decideLine` → LOADING, "still
  loading"). A blueprint that failed to load is logged once and skipped, never waited for.
  The straights' lateral shift comes from the start's joint, else the end's.
- Drawing: before the first click, a ghost of the current straight lies across the screen under the cursor. While
  dragging, the live segment is laid out (with the plan, so its joints are real) into Structurize ghosts (`_live_<n>`
  cache keys) with an outline per piece (green, red outside the claims or into a building) and the amber gap, only
  when the key (start, snapped end, plan layout version, options) changes (`LineSnap.relayout`). The live layout is the
  plan's layout with the live segment added, so the segments it attaches to (their joint, cap, shift and heights
  change) are drawn from it too and the plan skips its own ghosts for them meanwhile (`LineView.liveTouches`);
  before, only the joint piece came from the live layout and the old segment kept its cap and unshifted end beside
  the new run (Ethan's "snap: joint" preview). A label at the midpoint shows
  "N blocks · M pieces" or "Too short: needs ≥ N"; the cursor shows the gap, the too-short hint or what snapped. A
  click on a segment shorter than its need (`PieceLayout.need`; `LineSnap.canDrop`) is refused with a hint.
- Snapping (radius `LineSnap.radius`: 2.5 blocks, growing with the camera's height to 10). Start: a segment's end
  (its joint; a tower's centre and face middles are shown as cyan snap points), else onto a segment at the middle of
  its nearest piece (the branch splits it there: a T away from seams), else onto the segments' axes and their seam
  grid (start + whole straights), with a guide line. End: a segment's end in line with the start, else a
  perpendicular segment the run crosses (`LineSnap.crossing`; previewed split, split on the click: a T), else the
  nearest length the family's straights cover exactly between the joint / cap offsets (`LineSnap.snapLength`, no
  "short" gap; Shift = any length). The offsets come from the previous live layout (same start).
- Joint faces (`PieceLayout.Grid / attach / faceScore`, pure, from the turned blueprint's blocks): a run meets the
  joint piece's body, not its box. `Grid.inset` skips layers holding under half the blocks of the fullest layer (a
  tower's parapet overhang, an empty margin) on both the joint and the straight; the run starts right after the
  body face, and the sideways shift (within max(3, width)) is the best profile overlap, ties broken by centring the
  run on the face (a thin wall fits a wide tower face at several shifts; the old pick, 0, put it off-centre).
  Profiles compare height above the ground anchor, so pieces with different foundations line up. SelfCheck reads
  medievaloak's segment5 / tower5 from the MineColonies jar and checks every face and turn: the run never overlaps
  destructively, starts on the body face where that is safe, and its centreline is within 0.75 of the face centre.
- Overlap rule (`PieceLayout.destructive`, grids keep air apart from substitution): a run reaches into the joint's
  trim only as far as no cell would clear or replace a block of the other piece in EITHER build order (air pasted
  clears the world; substitution leaves it; survival builders work in any order); otherwise it stops at the joint's
  box (centred, with a cosmetic gap). The medievaloak tower's ring holds air next to the parapet, so runs meet the
  body on some faces and keep a 1-block gap on others. SelfCheck runs every bundled road / wall family's corner / T
  / cross / tower in every turn against its main straight both ways round: no destructive overlap; it prints which
  families meet the body exactly and which keep a gap. Planned and live pieces that would clear or replace each
  other's blocks (crossing segments, a road through a wall) are drawn red (`PieceCatalog.clash`, all pairs).
- Heights (`PieceLayout.levels`, per segment, saved): follow the ground (each piece at its centre's ground, as
  before), flat (every piece at the highest ground under any of its pieces), flat chain (the chain's shared level:
  `chainBase`, the highest ground of its segments so far; a higher segment lifts the earlier ones), smooth (raised so
  neighbours differ by at most 1, never below their ground); plus `dy` blocks (PgUp / PgDn). One Y at every seam: a
  joint piece stands as high as its own ground or the first piece of any run meeting it (`jointLevel`), and those
  first pieces (follow / smooth runs) rise to it; a cap or gate and the pieces next to it take their highest level
  (`shareSeams`); runs step only further from a joint (Ethan's sloped L: the horizontal run sat a block above the
  vertical one at the corner). The panel reads "Y 72 (+3)" for flat modes. Air under a
  raised piece (2+ blocks over the lowest ground under it) is drawn as an amber box: builders and pastes don't fill
  it, the blueprint stays as it is (a "fill foundations" option could come later).
- Flip (R, per segment): straights and gates turned 180° (a wall's other side out); joints re-score their turn
  against the flipped straights.
- Joint types (per joint cell, saved as `J x z type` rows; `Plan.J_*`): auto, tower, corner, inner corner, T, cross,
  none (butt joint). Auto: an L gets a corner, inner when the turn bends toward the walls' outside
  (`PieceLayout.innerTurn`: unflipped walls face out to the left of the first segment's travel; flip swaps), outer
  otherwise; three lines a T, four a cross; two in a straight line nothing. A tower is used when chosen, for old
  segments drawn with Towers, or as auto's fallback when it keeps strictly more whole straights than auto's piece
  (`PieceLayout.pickJoint`; among a role's variants the one leaving the fewest uncovered cells wins). The panel's
  joint picker sets the default for new joints (applied to a dropped segment's ends) or, in edit mode, the selected
  joint's type (C / T cycle).
- The "walls get removed" bug: medievaloak's T piece (9 x 9) reaches one block further than its corner (7 x 7), so
  walls sized for a corner lost a whole straight each (9 long, no shorter piece) when a third wall joined: 3 of 6
  straights kept. Auto now falls back to the tower there (all 6 kept; SelfCheck uses the real blueprints).
- Gaps: a gap the main straights leave is filled with the family's shorter pieces of the same width (any level /
  variant, end pieces), longest first. Length snapping keeps room at a free end for the joint the next segment will
  bring (`PieceCatalog.expectedReach`), so chained walls stay whole; Done / right-click / Esc trims a gap left at
  the chain's free end. Starting on another segment's free end grows that segment by the fewest cells that keep its
  straights whole once the joint takes its share (`LineSnap.extension`). Going straight on extends the last segment
  instead of butting a second one against it. SelfCheck prints, per bundled family, the uncovered cells at a T of
  walls sized for the corner with the T piece vs auto's pick (medievaloak / medievalbirch / medievaldarkoak / medievalspruce walls: 24 -> 2,
  via the tower; lostcity roads 6 -> 0; most roads without towers or shorter straights stay as they were).
- Ending: another segment's end within the snap radius (about 24 GUI pixels at the cursor's distance,
  `LineSnap.radiusPx`), from any side, wins over length snapping: in line with the start the joint is there;
  across a segment whose free end is off the line, that end moves along its own axis onto the line
  (`LineSnap.joinEnd`) and the joint forms there. Onto a segment's body: a T where the run crosses it. A live
  segment that would still clear or replace a planned piece is red with "crosses a segment without a joint". The
  target joint and its snap points show before the click.
- Edit mode (X or the Edit button; the line tools then draw nothing): hover highlights a joint (a segment end) or
  a segment; a click selects one. Dragging an end of the selected segment, the segment or the selected joint moves
  it (`Plan.move`, pure): segments ending at a moved point follow, along their axis by changing length, across it
  by shifting whole when their other end is free (else that component is dropped); directions and lengths >= 1 are
  kept, built / sending segments never move, a single dragged point snaps onto another end, joint types move with
  their joints. R flips, Del removes, C sets the joint type, Esc deselects (then leaves edit mode); leaving clears
  the selection. Draw mode keeps no selection.
- Joint alignment editor (`client/JointEditorScreen`; "Joint alignment…" in the Pieces editor, "Adjust alignment…"
  under the Edit-mode joint menu with the joint's style, family, role and turn pre-selected): pack, family, joint
  role (corner, inner corner, T, cross, tower, end cap), case (L-left / L-right / T / cross / end, flipped), the
  joint's turn (auto or fixed); a 3D view of the joint and its runs (`PieceView.renderScene`, each piece baked once)
  and a top-down map (joint box yellow, runs green, the selected one cyan, destructive red; click a run to select
  it). Per run: sideways shift and contact depth, the visible cross-section match, red when destructive (refused on
  Save). Saved per pack in `mccitybuilder/piecesets/<pack>.txt` as `align|<joint path>|<joint turn - d>|<straight
  path>|<straight turn - d>` -> `shift,depth` (keyed relative to the run's direction, so it holds for every
  orientation of the shape; the family and role are implied by the joint piece). `PieceLayout.fit` asks the
  override source (`setAlign`, the client's per-pack overrides by grid identity) before the memo and the search; a
  destructive override is ignored. Adjustments show in the planned walls at once: unsaved drafts live in a separate
  in-memory overlay read before the saved overrides and never written to the file (`PieceSets.alignLookup`); it is
  cleared whenever the editor screen goes (`removed()`: Esc, death, another screen), when the planner exits or hides.
  Save skips drafts that would break blocks and says how many (`PieceSets.saveable`); Reset returns a run to auto.
  The planner's exit closes the editor too, the editor returns to the planner only while it runs, and a leftover
  planner screen closes plainly (no soft-lock).
- Joint continuity (`PieceLayout.fit`, replacing the inset rule): the ground truth is the placed blocks. For each
  sideways shift (within max(3, width)) the run's end face (the straight's solid face toward the joint, trim skipped)
  meets the joint at the first layer from its face inward that holds blocks in front of at least half the face's
  visible cells (`PieceLayout.visible`: the ground layer and up; foundations are buried and never decide), unless
  that overlap is destructive in either build order (then the run stops at the joint's box). The shift with the
  best match of the two visible cross-sections wins (Jaccard: 1 = same shape, no air gap, no sideways step), then
  the shallowest contact, then centring on the joint's fullest visible layer, then the smallest shift. The joint's
  turn maximises every run's match plus block match, each line's straight as it is really placed (a line arriving
  at the joint runs the other way from one leaving it). The old rule scored the rows "as wide as most rows", which
  for the medieval walls were the nine buried foundation rows: it lined up the foundations and stepped the visible
  wall by one block (Ethan's screenshots). End caps meet their run the same way (turn along the line by face score;
  the cap follows the run's shift). SelfCheck (`JointCases`) lays out every bundled road / wall family's corner,
  inner corner, T, cross, tower and end cap, both turn directions, flipped and unflipped, voxelises the result
  (blueprint blocks at their final turned / offset positions, the same path as the ghosts) and asserts per run that
  the placed cross-sections match as well as any shift could (brute force on the blocks) and that the fit's own
  number equals the one measured on the blocks; medievaloak's wall body (rows 3..6) must continue exactly at every
  corner and T. It prints the continuous joints and a table of the rest with the rows that differ (most are a
  joint arm wider or narrower than the straight, or a tower's air ring). `./gradlew wallJoints -Pout=dir` writes
  top-down PNGs of every case (columns by highest block, joint box yellow, run boxes green, one contact strip per
  run); add `-PallLevels` for every level / variant number (names get " L<n>"; the default is each group's top). The test reader (`PackPieces`) takes the ground layer like the game: primary offset y minus the
  `groundlevel` tag's depth below the anchor (1 without one).
- Auto is from the topology only (`PieceLayout.autoRole`): every L gets the plain corner, turned to fit either way
  round (Ethan: a left turn giving a tower-like outer corner and a right turn a plain inner corner read as
  inconsistent), three = T, four = cross; the inner corner and the tower only when chosen per joint. Stand-ins
  (`PieceLayout.jointRoles`, used by the layout, the reach estimates and the joint menu): a missing corner takes the
  inner corner and the other way round, a missing T or cross takes the tower, else it is a butt joint (the old code
  put the corner at a T, which faces two of three walls). Roles come from the piece's file name
  (`PieceSets.role`, most specific keyword first: gate > inner > T > cross > corner/curve/outer > tower/pillar >
  stairs > end > straight); so original walls/cobble's `cobble_corner` (a 10 x 10 tower-like block) is its corner
  and `cobble_tower` its tower, nothing else. The tower's gap is still computed, for the edit-mode hint
  ("T · gap 4 (tower: 0)").
- A branch onto a wall's body (`LineSnap.crossing` + `Plan.split`: the end lands on the wall's axis cell, the click
  splits it there, three ends meet) lays out like any T; with no T piece or tower the butt joint's branch meets the
  owner wall's body with `fit` (the owner's straight stands in for the joint piece: the box may hold an air margin),
  so it starts at the first layer holding blocks in front of its face when that overlap is non-destructive, else at
  the box (a 1-block gap where the margin is air: birch, incan, lostcity walls - the table says so). SelfCheck's
  `T-split` cases build it through those two calls; `T-split-butt` covers the families without a T piece.
- Closing a ring: the end snap onto the chain's own start (any free end) wins over the T snap when that end is
  within the snap radius of the T cell, so a ring never splits its first wall a cell from its start and leaves the
  corner without a joint; each corner then has two ends and gets the chain's joint (towers when drawn with towers).
- Joint menu rows show the alignment too: "fits · 92% aligned" (green from 90%, amber below; the least run match at
  the joint, `PieceCatalog.jointMatch`), and each type is tried with every flip of the unlocked walls there (a corner
  piece's ledge sits on one side): the trial that fits, then matches best, then flips fewest wins, "flips 1 wall" says
  so, and applying it flips them. The harness's medievaloak inner corner cases (both turns, both flips, walls arriving
  or leaving) are all 0.77 (plinth row only) or 1.00, so an offset there in game isn't the turn or the flip.
- Towers: a run stops at a tower's box when the ring beside the body is air (an overlap would clear it in either
  build order); the edit-mode joint label says "1-block gap (the joint's ring holds air ...)" (`jointGapAir`). A
  thin wall on a wide tower is centred to half a block (shift 0 on ties: walls stay straight through a tower).
  Dormant: `PieceLayout.jointFirst` + `destructiveAfter` (only a run's air over a joint block counts, so a run may
  fill the ring: 127 of 162 tower cases would reach the body, medievaloak included; 35 still stop). It is OFF
  (`PlanView` sets it false) because nothing guarantees the paste order the rule assumes: the server pastes in
  the order it receives, a refused or timed-out batch drops only its own segment's rest while other segments' runs
  still go, Build selected can send runs before their joint's owner, and a joint next to an already built run is
  never first; in each case the joint's air would clear standing run blocks. `commit` still sends a segment's own
  joints first (harmless). Turning it on needs a server-side atomic paste of a joint together with its touching run
  ends, or a server re-check of a joint paste against standing blocks (skip its air cells over them); survival
  would keep the gap either way (builders take orders in any order).
- Drawing past a perpendicular wall's free end (`LineSnap.passing`): an unlocked free end within the snap radius of
  the live line, between its start and its end, moves onto the line along its own axis (`adjust`) and the live
  segment is split there on the click (`teeCell`): a T with the chosen joint type, shown live ("snap: T with a
  wall's end"), the same result as dragging that end onto the line in edit mode.
- Clear asks "Click Clear again to remove all N planned wall segments" (3 s), then removes every planned segment of
  the tool at once (the chain ends, the plan is laid out again) and says what stayed: "N built or sending walls kept
  (Remove deletes built ones)".
- Joint menu (edit mode, a click on a joint node; `LineView.openJointMenu`, `Plan.menuTypes`): the types that mean
  something for the node's shape (a free end: auto = its cap, end cap, tower standing at the wall's end, none; an L:
  auto, corner, inner corner, tower, none; a T: auto, T, tower, none; a cross: auto, cross, tower, none; straight
  on: auto, tower, none), the panel saying "This end" / "This joint"; an end's type is saved per cell like a joint's
  (`J x z type`, pruned with the end) and laid out at the free end by the same fit rules (a tower there is a joint
  with one run), only those the family has a piece for, each with a 3D thumbnail
  (`PieceView`), its name and "fits" / "won't fit: a wall lacks N blocks" (the same trial `setJointType` refuses
  with); hovering a row shows it in the world (the type is set without saving, `PlanView.relayout`, and put back),
  a click applies, Esc or any other press closes. C still cycles.
- Lengths: a chain's free end tiles exactly (no reserved room); when the next segment turns, the last one grows at
  the corner by what the corner takes (`chainGrowth`, shown live) and the new segment moves out with it. Edits never
  silently drop a straight: an edit drag snaps (without Shift) to positions where every run with joints at both
  ends still fits exactly (`Plan.fitMove`; the status turns amber with Shift when a piece would be lost), runs with a
  free end re-fit after a move or a joint-type change (`Plan.refitFree`, growing first), a joint type that would cost
  a run fixed at both ends a straight is refused with how many blocks it needs, and a drop that turns a corner into
  a T grows the affected walls' free ends (or warns).
- Edit mode UX: amber diamond rings at every segment end / joint and a small amber diamond mid-segment (never the
  citizen markers' squares), constant size, picked within 12 GUI pixels (nodes first, `LineSnap.pickHandle`); paler
  on hover, white-hot selected, green / orange / red while dragging; the joint types over the joints; a label for the selection ("Segment 2/5 · 9 blocks", "Whole wall
  · 5 segments · 47 blocks"); a status line while dragging, Esc cancels; a flash on release ("Moved", or why not);
  a hint at the cursor. Selection: a body click = that segment; Alt, a double-click or the Whole wall mode (Tab /
  the panel toggle) = every segment connected through joints (`Plan.connected`); a body drag moves the selection,
  attached ends follow. R and Del act on all selected segments, C only on a selected joint. The panel's Edit button
  is full width ("✎ Edit (X)" / "✎ Editing — click to draw", header "Wall · Editing").
- Clear (line panel): removes the tool's planned segments (built and sending ones, huts, decorations and the
  other tool's segments stay; `Plan.clearTool`), after a second click within 3 s; Undo restores them as one group.
- Removing (line tool): the planned segment under the cursor is highlighted; Del, right-click (no chain active) or
  Remove deletes it, and its neighbours' joints are laid out again; Undo (Backspace) with no chain brings removed
  segments back (last 32).
- Built records: a segment whose batches were all accepted stays in the plan as built (faint grey outline, no ghost,
  never built again, removable), so new segments snap and join onto walls already built. Built segments and those
  whose batches are on their way are never split (a T onto them gets no joint piece; overlaps show red). A built segment never owns
  a new joint piece: an unbuilt segment meeting it places that one.
- Committed pieces: each gets its own outline the height of its blueprint box, inset (Structurize draws its own
  white box around every ghost, which can't be turned off), green / red per piece, white for the selected segment,
  yellow for the hovered one.
- Panel: family, level or variant (variants: one, or mixed by piece index), height, Towers / Gate (walls: the
  selected segment, or the next one drawn; reset after a drop) / Flip, a 3D preview, the segment's numbers, Done /
  Undo / Remove / Build. A gear opens the piece-set editor as a popup beside the panel: which group plays each role
  (auto / none / any group of the family), saved per pack in `mccitybuilder/piecesets/<pack>.txt`.
- Preview (`client/PieceView`): Structurize has no GUI blueprint renderer (its `BlueprintRenderer` draws in the
  world stage only), so each piece is baked once into vertex buffers (one per entity render type, the block models
  the world uses, full-bright, at most 6000 blocks; animated / entity-model blocks such as chests are left out) and
  drawn with inventory lighting in a scissored box: slow turn, drag to turn, wheel to zoom. Arrows browse every piece the line uses at its level / variant
  (`PieceSets.browse`: straights, corner, inner corner, T, cross, end, gate, tower); the planned piece under the
  cursor shows instead; the corner button opens a large view.
- Style: the palette and the line tools use the colony's own style pack (`IColonyView.getStructurePack`) each time,
  unless the player picked another for that colony this client session; a style that isn't installed falls back to
  the first pack with a chat hint (once per colony and session).
- Build: each piece is a normal decoration from the real pack, through the server part as `piece_batch` messages of
  up to 16 pieces in drawing order; the server runs them one by one through the same DECO (creative paste) /
  DECO_ORDER (survival builder work order) checks as single decorations, plus: every path must be a road / wall
  piece, a batch can queue behind a running one (96 queued at most, one kind - creative or survival - at a time),
  at most 64 open road / wall decoration work orders per colony (counted from the colony's orders, so restarts don't
  forget them, and checked again right before each order is added), one summary message per run (placed / ordered
  vs skipped). The server answers every batch (`batch_result`: accepted, or refused with a reason); the client sends
  the next batch only after that answer (10 s timeout) and keeps a segment in the plan until all its batches were
  accepted (refused: it stays, its other batches are dropped, the reason is shown). A run whose piece never released
  is resumed by the player's next request. Payload protocol "3" (optional; "2" before single requests got answers): a client and server of different builds refuse the
  channel instead of mis-decoding; answers go only to clients that have the `batch_result` channel. Busy marks are
  time-stamped, and a request releases only its own (a stuck piece finishing late can't clear a newer mark). Survival is checked when ordered: builders later replace what is there
  when they build.

### Opening and staying inside the colony

- J opens only where the player stands in a chunk claimed by a colony (MineColonies' client claim data,
  `IColonyManager.getClaimData(dim, chunk).getOwningColony()`, 0 = none) whose view the client has, with the player a
  member (`IPermissions.isColonyMember`) and allowed `ACCESS_HUTS` (`ColonyGeometry.openCheck`, pure); otherwise an
  action-bar message: outside, not a member, no permission, or "still loading" (no claim data for the player's
  chunk and none within 8 chunks - `getClaimData` is null both for unclaimed and for not-yet-synced chunks - or the
  owning colony's view not synced yet). (It used to take the closest colony view, which from
  outside left the colony box and the loaded circle disjoint: the clamps fought and the camera glitched.)
- While open: the player's chunk owned by another colony or unclaimed closes it (`ColonyGeometry.leftColony`; a
  missing entry counts as unclaimed only when claim data exists within 2 chunks), and so does a colony
  box that overlaps the loaded circle by less than 8 blocks (`DroneCamera.outOfBounds`), before the clamps can pin the
  camera over unloaded land.

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
