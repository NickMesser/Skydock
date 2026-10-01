# Shipyard guide

Skydock's NeoForge 1.21.1 build lets you create an airship from a dock controller or assemble one of the built-in patterns. Fabric support is planned for a future release and is not available yet.

## Start a custom ship

1. Place a dock controller, then build within its facing outline (the direction you look when placing). Keep the hull separate from terrain. Scaffolding may support construction because it is excluded from the ship.
2. Connect every included block face-to-face to one helm. A ship needs exactly one helm.
3. Add lift cells and engines. A lift cell contributes lift and structural capacity; a connected cluster can grow in any direction without breaking its visual form.
4. Open the controller's **Manual** tab, choose **Inspect ship**, correct any reported issue, then choose **Launch ship**.

The four dock tiers are Scout, Brig, Cruiser, and Dreadnought. Their envelopes and block caps are shown in the main [README](../README.md#build-your-first-airship).

## Engines and fuel

Every engine has five fuel slots and accepts furnace fuel. The progression is:

| Tier | Engine | What improves |
| --- | --- | --- |
| 1 | Brass Engine | Baseline thrust, fuel use, and speed |
| 2 | Compound Engine | More thrust and efficiency |
| 3 | Turbine Engine | Higher thrust, efficiency, and top speed |
| 4 | Aether Drive | Highest thrust, efficiency, and top speed |

Use an engine to open its five-slot fuel and instruments screen. Sneaking does not open a separate engine screen.

## Patterns and resources

Each dock offers three patterns appropriate to its tier: a cutter, twin-hull ship, and hauler. Across the four dock tiers, the shipyard offers twelve built-in patterns.

Choose a pattern in the dock interface. Put its resources in one or more vanilla chests adjacent to the controller; touching double chests also work. The dock removes the full cost only when it can supply every requirement. In Creative mode, **Build ship** does not consume materials.

The decoration toggle controls the four cosmetic blocks: brass lantern, canvas awning, timber railing, and signal flag. When included, they add neither weight nor structural lift demand and they provide no lift. They still use a position in the dock's filled-block cap.

The pattern screen has a 3D preview: drag to orbit it, use the mouse wheel to zoom, and press **R** to reset the view. Its materials list shows both the available and required counts; scroll it to inspect the complete cost. Changing the decoration toggle refreshes the preview and material list.

Before starting, clear the full pattern footprint. Assembly places the ship in stages with particles marking its progress. Cancelling an assembly returns materials owned by its build journal. Once it completes, use the **Launch ship** footer to launch the finished craft.

## Devices and controls

Use the helm, mooring clamp, and crew seat normally for their quick action. Sneak and use any of them to open its device screen. Sneak and use a lift cell to view its connected cluster and total lift.

At the helm, **W/S** applies forward or reverse thrust, **A/D** turns, and **Space/Shift** climbs or descends. Press **C** for cruise and **R** to release the helm while remaining in first-person. During cruise, **W/S** changes speed and **A/D** steers; press **C** anywhere aboard to stop cruising. A crew seat can be used to sit and **R** gets up.

The mooring clamp can secure a ship only within five blocks of its reserved berth. Securing it stops movement and cruise.

## Docking

To redock, return within four blocks of the destination berth center, align within ten degrees of the berth facing, and slow below 1.6 blocks per second. Stand near the controller and select **Redock ship**. The berth must be clear and at least as large as the ship's current tier.

When a ship launches, the controller reserves the berth while the hull remains visible and usable. Redocking returns the real blocks and their inventories, allowing repair and expansion again.

## Model provenance

The model source archive is [assets-source/blockbench](../assets-source/blockbench). It holds 18 regular Blockbench models, including the connected railing’s post, side, and inventory parts, and 64 six-axis lift-cell variants. The v2 runtime palette contains twelve painted material textures. Its `.mjs` files are native Blockbench MCP input specifications; Minecraft does not generate these models procedurally at runtime. The [visual reference prompts](../assets-source/design-references/prompts-v2.md) document the art direction used to author them.
