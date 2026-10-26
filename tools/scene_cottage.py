#!/usr/bin/env python3
"""Writes the scene showcase's cottage as a structure file, as a structure block saves one.

    python3 tools/scene_cottage.py common/src/main/resources/assets/vellum/structures/cottage.nbt

The blocks carry their connection states (fences, panes, stairs), as a real save does.
"""
import sys

from structure_nbt import Build

b = Build()
W, D = 14, 11  # ground, x 0..13 and z 0..10
# House walls x 2..8, z 2..7; the door is in the south wall (z 7), facing the path.
X0, X1, Z0, Z1 = 2, 8, 2, 7

# Ground, with a path to the door and a pond in the front garden.
b.fill(0, 0, 0, W - 1, 0, D - 1, "minecraft:grass_block[snowy=false]")
for z in range(Z1 + 1, D):
    b.put(5, 0, z, "minecraft:dirt_path")
b.fill(8, 0, 8, 9, 0, 9, "minecraft:water[level=0]")
b.put(10, 0, 9, "minecraft:sand")
b.put(8, 1, 9, "minecraft:lily_pad")

# Foundation: a cobblestone ring with a spruce floor.
b.fill(X0, 1, Z0, X1, 1, Z1, "minecraft:spruce_planks")
for x in range(X0, X1 + 1):
    b.put(x, 1, Z0, "minecraft:cobblestone")
    b.put(x, 1, Z1, "minecraft:cobblestone")
for z in range(Z0, Z1 + 1):
    b.put(X0, 1, z, "minecraft:cobblestone")
    b.put(X1, 1, z, "minecraft:cobblestone")
b.put(5, 1, Z1 + 1, "minecraft:cobblestone_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")

# Walls: log corners, plank walls, a door and pane windows.
for y in range(2, 5):
    for x in range(X0, X1 + 1):
        b.put(x, y, Z0, "minecraft:oak_planks")
        b.put(x, y, Z1, "minecraft:oak_planks")
    for z in range(Z0, Z1 + 1):
        b.put(X0, y, z, "minecraft:oak_planks")
        b.put(X1, y, z, "minecraft:oak_planks")
    for x, z in ((X0, Z0), (X0, Z1), (X1, Z0), (X1, Z1)):
        b.put(x, y, z, "minecraft:oak_log[axis=y]")
door = "minecraft:oak_door[facing=south,half={},hinge=left,open=false,powered=false]"
b.put(5, 2, Z1, door.format("lower"))
b.put(5, 3, Z1, door.format("upper"))
pane_x = "minecraft:glass_pane[east=true,north=false,south=false,waterlogged=false,west=true]"
pane_z = "minecraft:glass_pane[east=false,north=true,south=true,waterlogged=false,west=false]"
for x in (3, 7):
    b.put(x, 3, Z1, pane_x)
b.put(4, 3, Z0, pane_x)
b.put(5, 3, Z0, pane_x)
b.put(6, 3, Z0, pane_x)
for z in (4, 5):
    b.put(X0, 3, z, pane_z)
    b.put(X1, 3, z, pane_z)
lantern = "minecraft:lantern[hanging=false,waterlogged=false]"
b.put(4, 4, Z1 + 1, "minecraft:lantern[hanging=true,waterlogged=false]")

# Inside: a furnace, a crafting table with a lantern, a chest, a bed and a barrel.
b.put(3, 2, 3, "minecraft:furnace[facing=south,lit=true]")
b.put(4, 2, 3, "minecraft:crafting_table")
b.put(4, 3, 3, lantern)
b.put(7, 2, 3, "minecraft:chest[facing=south,type=single,waterlogged=false]")
b.put(7, 2, 5, "minecraft:red_bed[facing=north,occupied=false,part=head]")
b.put(7, 2, 6, "minecraft:red_bed[facing=north,occupied=false,part=foot]")
b.put(3, 2, 6, "minecraft:barrel[facing=up,open=false]")
b.put(3, 3, 6, "minecraft:potted_red_tulip")

# Wall plate and gables, under a spruce roof that overhangs a block all round.
for x in range(X0, X1 + 1):
    b.put(x, 5, Z0, "minecraft:stripped_oak_log[axis=x]")
    b.put(x, 5, Z1, "minecraft:stripped_oak_log[axis=x]")
for x in (X0, X1):
    for z in range(Z0 + 1, Z1):
        b.put(x, 5, z, "minecraft:oak_planks")
    for z in range(Z0 + 1, Z1):
        b.put(x, 6, z, "minecraft:oak_planks")
    for z in range(Z0 + 2, Z1 - 1):
        b.put(x, 7, z, "minecraft:oak_planks")
b.put(X0, 6, 4, "minecraft:glass_pane[east=false,north=true,south=true,waterlogged=false,west=false]")
b.put(X0, 6, 5, "minecraft:glass_pane[east=false,north=true,south=true,waterlogged=false,west=false]")
stairs = "minecraft:spruce_stairs[facing={},half=bottom,shape=straight,waterlogged=false]"
for x in range(X0 - 1, X1 + 2):
    for i in range(4):
        b.put(x, 5 + i, Z0 - 1 + i, stairs.format("south"))
        b.put(x, 5 + i, Z1 + 1 - i, stairs.format("north"))
# A chimney through the back of the roof.
for y in range(5, 10):
    b.put(7, y, 3, "minecraft:cobblestone")
b.put(7, 10, 3, "minecraft:cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]")

# The front fence, with a gate on the path.
fence = "minecraft:oak_fence[east={},north=false,south=false,waterlogged=false,west={}]"
for x in range(0, W):
    if x == 5:
        b.put(x, 1, D - 1, "minecraft:oak_fence_gate[facing=south,in_wall=false,open=false,powered=false]")
        continue
    east = "true" if x < W - 1 else "false"
    west = "true" if x > 0 else "false"
    b.put(x, 1, D - 1, fence.format(east, west))
for x, flower in ((1, "poppy"), (2, "dandelion"), (3, "cornflower"), (7, "oxeye_daisy"), (6, "azure_bluet")):
    b.put(x, 1, D - 2, "minecraft:" + flower)
b.put(1, 1, 8, "minecraft:short_grass")
b.put(12, 1, 8, "minecraft:short_grass")
b.put(10, 1, 8, "minecraft:fern")

# A tree beside the house.
TX, TZ = 12, 4
for y in range(1, 6):
    b.put(TX, y, TZ, "minecraft:oak_log[axis=y]")
leaves = "minecraft:oak_leaves[distance={},persistent=true,waterlogged=false]"
for y, r in ((4, 2), (5, 2), (6, 1), (7, 1)):
    for x in range(TX - r, TX + r + 1):
        for z in range(TZ - r, TZ + r + 1):
            if not (0 <= x < W and 0 <= z < D) or b.get(x, y, z):
                continue
            if r == 2 and abs(x - TX) == 2 and abs(z - TZ) == 2:
                continue
            if y == 7 and abs(x - TX) + abs(z - TZ) > 1:
                continue
            b.put(x, y, z, leaves.format(max(1, abs(x - TX) + abs(z - TZ))))

b.save(sys.argv[1])
