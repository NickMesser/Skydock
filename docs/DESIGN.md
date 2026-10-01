# Skydock implementation design

The supplied airship plan is implemented with Architectury as the common API boundary, targeting NeoForge first. Minecraft remains 1.21.1 and Java remains 21. Fabric is a future loader adapter; its availability must never be inferred from the common module compiling.

## Modules

- `common`: Architectury registries, lifecycle/tick events, command registration, networking, reload listeners, menus and keys; dock validation, transfer journal, ship state, physics, collision, block interactions, devices, client meshes, and common mixins.
- `neoforge`: the mod manifest and entry point, client bootstrap, and NeoForge render-stage callback into the shared renderer.

Common source must not import NeoForge APIs. Where a future feature needs capabilities, loader chunk-ticket policies, or loader-specific model data, put the implementation behind a narrow platform adapter. Keep ship math, validation and persistence independent of that adapter.

## Authorities

The server owns ship poses, fuel consumption, launch validation, ownership, reservations, and block transfers. The client submits bounded control inputs and use intent; it does not choose a shipyard position to manipulate. Use intent includes the viewed ship timestamp and a local ray. The server reconstructs that view from its short pose history, validates finite values, player proximity, look direction and reach, then projects the ray onto the current ship. It raycasts again against current terrain, entities and hulls, checks ownership, and opens the real block's menu. This keeps clicks aligned with interpolated moving hulls. Each open remote menu is associated with its exact menu instance and source block, and is invalidated on distance, removal, redock, death, permission loss, or dimension change.

The hidden shipyard holds the actual block entities. Client hull snapshots omit inventory contents and send only vanilla-style update tags when visual block-entity data is needed. Inventories move through ordinary server menu packets.

## Transfers

1. Scan the legal dock box and require one connected helm, sufficient lift/cap, loaded chunks, no attached blocks beyond the box, and no forbidden/fluid blocks.
2. Reserve a unique yard region and save the source block states and block-entity NBT in a `launching` journal entry.
3. Flush the journal before edits. Copy the hull to the yard and flush the copied chunks. Remove source block entities before their blocks to suppress inventory drops.
4. Mark the ship active and persist. Its original controller position remains reserved even while unloaded.
5. For redock, validate destination permissions, size, clearance, pose and speed. Snapshot current yard state, save a `redocking` journal, copy to the berth, flush, and then clear the yard and retire the ship record.

The transfer journal is replayable at server startup. This is a Minecraft world-save protocol, not a general database transaction: storage faults, external world editors, and mod-defined side effects during block replacement still require operator recovery. Keep backups of worlds used to test an alpha.

## Flight and hulls

Flight has translation and yaw only. Lift supports hover and vertical control; engines supply thrust and steering while burning standard furnace fuels. Manual controls expire when updates stop, the player leaves the helm, logs out, or dies. Cruise is an explicit, saved speed governor independent of pilot input, so it continues after releasing the helm and across world reloads. It disengages on collision, mooring, or fuel exhaustion. Throttle, climb and angular velocity ramp toward their targets; lateral drag helps velocity follow the bow. Steering uses actual hull dimensions and mass. Collision uses the tight hull bounds and short motion substeps, stopping at terrain, other hulls, the world border or height limits.

Client motion uses timestamped samples with a short interpolation buffer and at most two ticks of extrapolation. Hull snapshot refreshes preserve the existing motion timeline. Ships and riders advance after vanilla entity ticks, so the camera and hull render over the same previous/current interval. Pilots retain a local deck anchor while driving; released crew retain ordinary movement. Local first-person yaw follows deck rotation without changing the selected camera perspective. Each positional player packet is preceded by bounded deck-frame metadata: a sequence, ship, sampled timestamp and local feet position. The server accepts it only for the attached ship when it reconstructs the adjacent vanilla packet from pose history, then expresses that local target in the current authoritative pose before ordinary vanilla movement validation. A server correction carries an authoritative local position back to the interpolated client hull.

Players joining on a deck receive a temporary local boarding anchor. The server holds that position until the client acknowledges its complete hull snapshot, then completes a normal position sync. This prevents initial client gravity from carrying a returning player through a hull that has not arrived yet.

Nearby entity motion resolves in ship-local space against unrotated hull voxel shapes, then rotates the clipped delta back to world so one-block corridors stay walkable at any yaw. Ship–ship and impact checks still use enclosing world boxes of yawed voxels as a conservative broad phase. An entity acquires a per-instance ship attachment from exact foot support and keeps it while a real hull collision shape remains below its footprint within ordinary jump height. This carries stair transitions and jumps in the ship frame without forcing grounded state or clearing fall distance. Walking or jumping beyond the hull column detaches and inherits the carrier point velocity once, including angular tangential motion. Players retain ordinary movement, combat and inventories in their original dimension.

## Adding Fabric later

Add a `fabric` module, Fabric metadata, and an entry point that calls `Skydock.init()`. Bootstrap `SkydockClient.init()` on the client and forward the corresponding Fabric world-render event to `ShipRenderer`. Add Fabric to Architectury's enabled platforms and package `transformProductionFabric` in the Fabric jar. Use the same common resources, payload types, and mixins. Then run the same external Marionette scenarios on Fabric, including resource loading, inventory transfer, walking on yawed hulls and remote menu actions. Do not publish a Fabric jar until those runtime checks pass.

## Staging beyond the v1 core

Large-hull profiling, two-client latency tests, foreign machine matrices, dynamic block-entity render data, durable transfer fault injection, and per-mod hooks are continuing hardening work. Cosmetic flight tilt, weather, balloon damage, weapons, world-generated berths and Create contraption networks remain later features from the original plan.
