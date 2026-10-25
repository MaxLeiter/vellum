#!/usr/bin/env python3
"""Writes the Skyhaven showcase's island as a structure file: a small village on a floating island.

    python3 tools/scene_island.py common/src/main/resources/assets/vellum/structures/sky_island.nbt

A cottage with a smoking chimney, a well, a wheat farm, a cherry tree and an oak, and a pond with a dock that spills
off the island's edge as a waterfall. The grass is at y 6; the page (showcase/island.html) walks villagers and
animals on it (y 7) and floats boats on the pond. The island's underside tapers to a point.
"""
import math
import random
import sys

from structure_nbt import Build

rng = random.Random(12)
b = Build()

N = 23            # x and z run 0..22
CX, CZ = 11, 11
TOP = 6


def inside(x, z, scale):
    dx, dz = x - CX, z - CZ
    a = math.atan2(dz, dx)
    r = (8.4 + 0.8 * math.sin(3 * a + 0.4) + 0.5 * math.sin(5 * a + 2.0)) * scale
    return math.hypot(dx, dz) <= r


SHRINK = {TOP: 1.0, 5: 0.86, 4: 0.7, 3: 0.54, 2: 0.39, 1: 0.25, 0: 0.11}
for y, s in SHRINK.items():
    for x in range(N):
        for z in range(N):
            if not inside(x, z, s):
                continue
            if y == TOP:
                state = "minecraft:grass_block[snowy=false]"
            elif y == 5:
                state = "minecraft:dirt"
            elif y >= 3:
                roll = rng.random()
                state = "minecraft:coal_ore" if roll < 0.05 else "minecraft:andesite" if roll < 0.2 else "minecraft:stone"
            else:
                state = "minecraft:stone" if rng.random() < 0.6 else "minecraft:tuff"
            b.put(x, y, z, state)

# Moss and roots under the edge of the grass.
for (x, y, z) in list(b.blocks):
    if y == 5 and b.get(x, 4, z) is None and rng.random() < 0.35:
        b.put(x, 4, z, "minecraft:hanging_roots[waterlogged=false]")


def on_top(x, z):
    return b.get(x, TOP, z) == "minecraft:grass_block[snowy=false]"


# ---- The pond, south-east, spilling off the edge ----
POND = set()
for x in range(N):
    for z in range(N):
        if b.get(x, TOP, z) and ((x - 15.5) / 2.9) ** 2 + ((z - 15.2) / 2.3) ** 2 <= 1:
            POND.add((x, z))
for x, z in POND:
    b.put(x, TOP, z, "minecraft:water[level=0]")
    if b.get(x, TOP - 1, z):
        b.put(x, TOP - 1, z, "minecraft:sand" if rng.random() < 0.7 else "minecraft:clay")
# A channel from the pond to the edge, and the fall.
fx = 17
fz = max(z for x, z in POND if x == fx)
while b.get(fx, TOP, fz + 1) is not None:
    fz += 1
    b.put(fx, TOP, fz, "minecraft:water[level=0]")
    b.put(fx, TOP - 1, fz, "minecraft:sand")
for y in range(TOP, -1, -1):
    if b.get(fx, y, fz + 1) is not None:
        break
    b.put(fx, y, fz + 1, "minecraft:water[level=8]")
# Lily pads and sugar cane round the pond.
for x, z in ((14, 14), (16, 16)):
    b.put(x, TOP + 1, z, "minecraft:lily_pad")
for x, z in ((12, 15), (12, 16), (18, 13)):
    if on_top(x, z):
        b.put(x, TOP, z, "minecraft:sand")
        b.put(x, TOP + 1, z, "minecraft:sugar_cane[age=0]")
        b.put(x, TOP + 2, z, "minecraft:sugar_cane[age=0]")

# The dock: spruce slabs out over the water from the north shore, a post with a lantern.
slab = "minecraft:spruce_slab[type=top,waterlogged=true]"
for z in (12, 13, 14):
    b.put(14, TOP, z, slab if (14, z) in POND else "minecraft:spruce_planks")
b.put(13, TOP + 1, 12, "minecraft:spruce_fence[east=false,north=false,south=false,waterlogged=false,west=false]")
b.put(13, TOP + 2, 12, "minecraft:lantern[hanging=false,waterlogged=false]")

# ---- The cottage, north-west: x 4..8, z 4..8, door to the south ----
X0, X1, Z0, Z1 = 4, 8, 4, 8
b.fill(X0, TOP, Z0, X1, TOP, Z1, "minecraft:cobblestone")
b.fill(X0 + 1, TOP, Z0 + 1, X1 - 1, TOP, Z1 - 1, "minecraft:spruce_planks")
for y in range(TOP + 1, TOP + 4):
    for x in range(X0, X1 + 1):
        for z in (Z0, Z1):
            b.put(x, y, z, "minecraft:birch_planks")
    for z in range(Z0, Z1 + 1):
        for x in (X0, X1):
            b.put(x, y, z, "minecraft:birch_planks")
    for x, z in ((X0, Z0), (X0, Z1), (X1, Z0), (X1, Z1)):
        b.put(x, y, z, "minecraft:stripped_spruce_log[axis=y]")
door = "minecraft:spruce_door[facing=south,half={},hinge=left,open=false,powered=false]"
b.put(6, TOP + 1, Z1, door.format("lower"))
b.put(6, TOP + 2, Z1, door.format("upper"))
pane_x = "minecraft:glass_pane[east=true,north=false,south=false,waterlogged=false,west=true]"
pane_z = "minecraft:glass_pane[east=false,north=true,south=true,waterlogged=false,west=false]"
for x in (5, 7):
    b.put(x, TOP + 2, Z1, pane_x)
    b.put(x, TOP + 2, Z0, pane_x)
for z in (5, 7):
    b.put(X0, TOP + 2, z, pane_z)
    b.put(X1, TOP + 2, z, pane_z)
b.put(6, TOP + 3, Z1 + 1, "minecraft:lantern[hanging=true,waterlogged=false]")
b.put(5, TOP + 1, Z1 + 1, "minecraft:potted_poppy")
# Inside, seen through the windows.
b.put(5, TOP + 1, 5, "minecraft:crafting_table")
b.put(7, TOP + 1, 5, "minecraft:barrel[facing=up,open=false]")
b.put(7, TOP + 1, 7, "minecraft:white_bed[facing=north,occupied=false,part=foot]")
b.put(7, TOP + 1, 6, "minecraft:white_bed[facing=north,occupied=false,part=head]")
# Gable ends and a dark oak roof along x, overhanging a block.
for x in (X0, X1):
    for z in range(Z0 + 1, Z1):
        b.put(x, TOP + 4, z, "minecraft:birch_planks")
    b.put(x, TOP + 5, 6, "minecraft:birch_planks")
stairs = "minecraft:dark_oak_stairs[facing={},half=bottom,shape=straight,waterlogged=false]"
for x in range(X0 - 1, X1 + 2):
    for i in range(3):
        b.put(x, TOP + 4 + i, Z0 - 1 + i, stairs.format("south"))
        b.put(x, TOP + 4 + i, Z1 + 1 - i, stairs.format("north"))
    b.put(x, TOP + 6, 6, "minecraft:dark_oak_slab[type=bottom,waterlogged=false]")
# The chimney, at the back.
for y in range(TOP + 1, TOP + 8):
    b.put(X1 - 1, y, Z0, "minecraft:cobblestone" if y < TOP + 7 else "minecraft:campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]")
b.put(X1 - 1, TOP + 1, Z0 + 1, "minecraft:furnace[facing=south,lit=true]")

# ---- The well, east of the cottage ----
WX, WZ = 13, 6
for dx in (-1, 0, 1):
    for dz in (-1, 0, 1):
        b.put(WX + dx, TOP, WZ + dz, "minecraft:cobblestone")
        if dx or dz:
            b.put(WX + dx, TOP + 1, WZ + dz, "minecraft:cobblestone")
b.put(WX, TOP, WZ, "minecraft:water[level=0]")
b.put(WX, TOP + 1, WZ, "minecraft:water[level=0]")
for dx, dz in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
    for y in (TOP + 2, TOP + 3):
        b.put(WX + dx, y, WZ + dz, "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]")
b.fill(WX - 1, TOP + 4, WZ - 1, WX + 1, TOP + 4, WZ + 1, "minecraft:cobblestone_slab[type=bottom,waterlogged=false]")
b.put(WX, TOP + 4, WZ, "minecraft:cobblestone")

# ---- The farm, south-west: farmland round a water row ----
for x in range(3, 9):
    for z in range(12, 17):
        if not b.get(x, TOP, z):
            continue
        if z == 14:
            b.put(x, TOP, z, "minecraft:water[level=0]")
        else:
            b.put(x, TOP, z, "minecraft:farmland[moisture=7]")
            crop = "minecraft:carrots[age=7]" if x >= 7 else "minecraft:wheat[age=7]" if rng.random() < 0.85 else "minecraft:wheat[age=5]"
            b.put(x, TOP + 1, z, crop)
b.put(9, TOP + 1, 12, "minecraft:composter[level=5]")
b.put(9, TOP + 1, 13, "minecraft:hay_block[axis=y]")
b.put(9, TOP + 2, 13, "minecraft:hay_block[axis=x]")
b.put(9, TOP + 1, 14, "minecraft:hay_block[axis=z]")

# ---- Paths: from the door south to the farm, east to the well and the dock ----
path = set()
for z in range(Z1 + 1, 12):
    path.add((6, z))
for x in range(7, 14):
    path.add((x, 10))
for z in range(8, 10):
    path.add((13, z))
path.add((13, 11))
for x, z in path:
    if on_top(x, z):
        b.put(x, TOP, z, "minecraft:dirt_path")
for x, z in ((10, 9), (10, 11)):
    if on_top(x, z):
        b.put(x, TOP + 1, z, "minecraft:spruce_fence[east=false,north=false,south=false,waterlogged=false,west=false]")
        b.put(x, TOP + 2, z, "minecraft:lantern[hanging=false,waterlogged=false]")


# ---- Trees: a cherry by the pond, an oak at the north-east, a bush by the farm ----
def canopy(cx, cy, cz, r, leaves):
    for x in range(cx - r, cx + r + 1):
        for y in range(cy - 1, cy + 2):
            for z in range(cz - r, cz + r + 1):
                d = abs(x - cx) + abs(z - cz) + abs(y - cy) * 1.5
                if d <= r + 0.5 and b.get(x, y, z) is None and rng.random() < 0.92:
                    b.put(x, y, z, leaves)


for y in range(TOP + 1, TOP + 5):
    b.put(17, y, 9, "minecraft:cherry_log[axis=y]")
b.put(18, TOP + 4, 9, "minecraft:cherry_log[axis=x]")
canopy(17, TOP + 6, 9, 3, "minecraft:cherry_leaves[distance=1,persistent=true,waterlogged=false]")
for x, z in ((16, 11), (18, 10), (15, 9), (19, 8)):
    if on_top(x, z):
        b.put(x, TOP + 1, z, "minecraft:pink_petals[facing=north,flower_amount=4]")

for y in range(TOP + 1, TOP + 6):
    b.put(16, y, 3, "minecraft:oak_log[axis=y]")
canopy(16, TOP + 6, 3, 2, "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]")
b.put(2, TOP + 1, 10, "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]")
b.put(2, TOP + 1, 11, "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]")

# Flowers and grass on what is left.
FLOWERS = ["poppy", "dandelion", "cornflower", "oxeye_daisy", "azure_bluet", "allium"]
for x in range(N):
    for z in range(N):
        if on_top(x, z) and b.get(x, TOP + 1, z) is None:
            roll = rng.random()
            if roll < 0.1:
                b.put(x, TOP + 1, z, "minecraft:short_grass")
            elif roll < 0.15:
                b.put(x, TOP + 1, z, "minecraft:" + rng.choice(FLOWERS))

# Nobody sees the inside of the island: leave it out.
TERRAIN = ("minecraft:grass_block[snowy=false]", "minecraft:stone", "minecraft:dirt", "minecraft:andesite", "minecraft:tuff", "minecraft:coal_ore")
b.hollow(lambda state: state in TERRAIN)
b.save(sys.argv[1])
