#!/usr/bin/env python3
"""Writes the Quest showcase's diorama as a structure file: a ruined portal on a floating island.

    python3 tools/scene_portal.py common/src/main/resources/assets/vellum/structures/ruined_portal.nbt

The portal frame stands at x 6..9, y 8..12, z 8, with three blocks missing; the page (showcase/quest.html) puts
them back, and the portal inside, when the quest is accepted. The island tapers to a point underneath, with ores in
its stone and roots hanging off its edge. Netherrack has spread round the portal, and lava spills off one side.
"""
import math
import random
import sys

from structure_nbt import Build

rng = random.Random(7)
b = Build()

CX, CZ = 8, 8      # the island's middle
TOP = 7            # the grass layer
# The frame's missing blocks, which the page adds back.
MISSING = {(9, 12), (8, 12), (9, 11)}


def radius(x, z, scale):
    """Whether (x, z) is inside the island's wobbly outline, shrunk by scale."""
    dx, dz = x - CX, z - CZ
    a = math.atan2(dz, dx)
    r = (6.6 + 0.9 * math.sin(3 * a + 1.1) + 0.5 * math.sin(5 * a + 0.3)) * scale
    return math.hypot(dx, dz) <= r


# The body: grass on dirt on stone, each layer smaller, down to a point.
SHRINK = {TOP: 1.0, 6: 0.9, 5: 0.76, 4: 0.62, 3: 0.48, 2: 0.34, 1: 0.21, 0: 0.09}
ORES = ["minecraft:coal_ore", "minecraft:iron_ore", "minecraft:gold_ore", "minecraft:copper_ore"]
for y, s in SHRINK.items():
    for x in range(17):
        for z in range(17):
            if not radius(x, z, s):
                continue
            if y == TOP:
                state = "minecraft:grass_block[snowy=false]"
            elif y == 6:
                state = "minecraft:dirt" if rng.random() < 0.85 else "minecraft:coarse_dirt"
            elif y >= 3:
                roll = rng.random()
                state = rng.choice(ORES) if roll < 0.07 else "minecraft:andesite" if roll < 0.2 else "minecraft:stone"
            else:
                roll = rng.random()
                state = "minecraft:tuff" if roll < 0.3 else "minecraft:deepslate" if roll < 0.8 else "minecraft:deepslate_gold_ore" if roll < 0.86 else "minecraft:cobbled_deepslate"
            b.put(x, y, z, state)

# Roots hang off the dirt where the layer below stops short.
for (x, y, z), state in list(b.blocks.items()):
    if y == 6 and b.get(x, 5, z) is None and rng.random() < 0.45:
        b.put(x, 5, z, "minecraft:hanging_roots[waterlogged=false]")

# Netherrack spreads round the portal, with magma and nether gold here and there.
for x in range(17):
    for z in range(17):
        if b.get(x, TOP, z) is None:
            continue
        d = math.hypot(x - 7.5, (z - 8) * 1.3)
        if d < 2.3 or (d < 4.2 and rng.random() < 1.25 - d / 4.2):
            roll = rng.random()
            state = "minecraft:magma_block" if roll < 0.12 else "minecraft:nether_gold_ore" if roll < 0.18 else "minecraft:netherrack"
            b.put(x, TOP, z, state)
            if rng.random() < 0.5 and b.get(x, 6, z):
                b.put(x, 6, z, "minecraft:netherrack")

# A cracked stone-brick floor under the portal.
BRICKS = ["minecraft:stone_bricks", "minecraft:cracked_stone_bricks", "minecraft:mossy_stone_bricks"]
for x in range(5, 11):
    for z in range(7, 10):
        if rng.random() < 0.78:
            b.put(x, TOP, z, rng.choice(BRICKS))
b.put(5, TOP + 1, 9, "minecraft:stone_brick_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")
b.put(10, TOP + 1, 7, "minecraft:stone_brick_slab[type=bottom,waterlogged=false]")

# The frame: obsidian with crying obsidian in it, three blocks gone.
CRYING = {(6, 10), (7, 8), (9, 9), (6, 12)}
for x in range(6, 10):
    for y in range(8, 13):
        edge = x in (6, 9) or y in (8, 12)
        if not edge or (x, y) in MISSING:
            continue
        b.put(x, y, 8, "minecraft:crying_obsidian" if (x, y) in CRYING else "minecraft:obsidian")
# Where the missing blocks fell.
b.put(10, 8, 10, "minecraft:obsidian")
b.put(11, 8, 9, "minecraft:crying_obsidian")
b.put(4, 8, 7, "minecraft:obsidian")

# Loot, gold and a sign of the Nether around it.
b.put(10, 8, 8, "minecraft:chest[facing=west,type=single,waterlogged=false]")
b.put(4, 8, 9, "minecraft:gold_block")
b.put(3, 8, 8, "minecraft:gilded_blackstone")
b.put(5, 8, 6, "minecraft:blackstone")
b.put(11, 8, 6, "minecraft:polished_blackstone_bricks")
b.put(11, 9, 6, "minecraft:polished_blackstone_brick_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]")
b.put(9, 8, 6, "minecraft:lantern[hanging=false,waterlogged=false]")
b.put(7, 8, 10, "minecraft:crimson_roots")
b.put(8, 8, 6, "minecraft:crimson_fungus")
b.put(6, 8, 6, "minecraft:fire[age=0,east=false,north=false,south=false,up=false,west=false]")

# A lava pool by the east edge that spills over it and falls down the island's side.
for x, z in ((12, 10), (13, 10), (12, 11), (13, 9)):
    if b.get(x, TOP, z):
        b.put(x, TOP, z, "minecraft:lava[level=0]")
for x, z in ((11, 10), (11, 11), (12, 9), (12, 12), (13, 11), (14, 9), (14, 10)):
    if b.get(x, TOP, z) and not b.get(x, TOP, z).startswith("minecraft:lava"):
        b.put(x, TOP, z, "minecraft:magma_block")
edge = max(x for x in range(17) if b.get(x, TOP, 10) is not None)
b.put(edge, TOP, 10, "minecraft:lava[level=0]")
for y in range(TOP - 1, 0, -1):
    if b.get(edge + 1, y, 10) is not None:
        break
    b.put(edge + 1, y, 10, "minecraft:lava[level=8]")

# The rest of the grass: a few flowers and ferns, and a dead tree on the far side.
for x in range(17):
    for z in range(17):
        if b.get(x, TOP, z) == "minecraft:grass_block[snowy=false]" and b.get(x, TOP + 1, z) is None:
            roll = rng.random()
            if roll < 0.12:
                b.put(x, TOP + 1, z, "minecraft:short_grass")
            elif roll < 0.16:
                b.put(x, TOP + 1, z, "minecraft:fern")
            elif roll < 0.18:
                b.put(x, TOP + 1, z, rng.choice(["minecraft:poppy", "minecraft:dandelion"]))
TX, TZ = 4, 4
for y in range(TOP + 1, TOP + 5):
    b.put(TX, y, TZ, "minecraft:dark_oak_log[axis=y]")
b.put(TX + 1, TOP + 4, TZ, "minecraft:dark_oak_log[axis=x]")
b.put(TX + 2, TOP + 4, TZ, "minecraft:dark_oak_log[axis=x]")
b.put(TX, TOP + 5, TZ, "minecraft:dark_oak_log[axis=y]")
for x, y, z in ((TX - 1, 12, TZ), (TX, 13, TZ), (TX, 12, TZ - 1), (TX + 2, 12, TZ), (TX + 3, 12, TZ), (TX + 2, 13, TZ),
                (TX + 2, 12, TZ + 1), (TX - 1, 13, TZ - 1)):
    if b.get(x, y, z) is None:
        b.put(x, y, z, "minecraft:dark_oak_leaves[distance=1,persistent=true,waterlogged=false]")

b.save(sys.argv[1])
