#!/usr/bin/env python3
"""Writes the Smelter showcase's machine as a structure file: four furnaces fed by hopper lines, on a tiled plate.

    python3 tools/scene_smelter.py common/src/main/resources/assets/vellum/structures/smelter.nbt

Ore goes in the top chest, runs east along a hopper line and drops into each furnace. Fuel comes the same way into
their backs. Hoppers under the furnaces pull the ingots out into the chests in front, and a comparator on the last
chest lights a lamp. The page (showcase/smelter.html) names the same boxes as its parts.
"""
import sys

from structure_nbt import Build

b = Build()
W, D = 12, 9  # the plate, x 0..11 and z 0..8

# The plate: deepslate tiles under a checkered floor with a dark rim.
b.fill(0, 0, 0, W - 1, 0, D - 1, "minecraft:deepslate_tiles")
for x in range(W):
    for z in range(D):
        if x in (0, W - 1) or z in (0, D - 1):
            state = "minecraft:polished_deepslate"
        else:
            state = "minecraft:smooth_stone" if (x + z) % 2 == 0 else "minecraft:polished_andesite"
        b.put(x, 1, z, state)


def hopper(facing):
    return f"minecraft:hopper[enabled=true,facing={facing}]"


def chest(facing, kind):
    return f"minecraft:chest[facing={facing},type={kind},waterlogged=false]"


# Furnaces in a row, x 4..7, facing south (toward the camera).
for x in range(4, 8):
    b.put(x, 3, 4, "minecraft:furnace[facing=south,lit=true]")
    b.put(x, 2, 4, hopper("south"))      # output: pulls from the furnace above, pushes into the chest in front
    b.put(x, 4, 4, hopper("down"))       # ore into the furnace's top
    b.put(x, 5, 4, hopper("east" if x < 7 else "down"))  # the ore line
    b.put(x, 3, 3, hopper("south"))      # fuel into the furnace's back
    b.put(x, 4, 3, hopper("east" if x < 7 else "down"))  # the fuel line

# Double chests facing south: the west half is type=right, the east half type=left.
for x0 in (4, 6):
    b.put(x0, 2, 5, chest("south", "right"))
    b.put(x0 + 1, 2, 5, chest("south", "left"))
b.put(4, 6, 4, chest("south", "right"))  # ore in
b.put(5, 6, 4, chest("south", "left"))
b.put(4, 5, 3, chest("south", "right"))  # fuel in
b.put(5, 5, 3, chest("south", "left"))

# A comparator reads the last output chest and lights a lamp.
b.put(8, 2, 5, "minecraft:comparator[facing=west,mode=compare,powered=true]")
b.put(9, 2, 5, "minecraft:redstone_wire[east=side,north=none,power=9,south=none,west=side]")
b.put(10, 2, 5, "minecraft:redstone_lamp[lit=true]")

# Supplies: barrels, raw iron and coal waiting by the machine.
b.put(1, 2, 1, "minecraft:barrel[facing=up,open=false]")
b.put(2, 2, 1, "minecraft:barrel[facing=north,open=false]")
b.put(1, 3, 1, "minecraft:barrel[facing=up,open=false]")
b.put(1, 2, 2, "minecraft:coal_block")
b.put(1, 2, 3, "minecraft:coal_block")
b.put(2, 2, 2, "minecraft:coal_block")
b.put(1, 3, 2, "minecraft:coal_block")
b.put(1, 2, 6, "minecraft:raw_iron_block")
b.put(1, 2, 7, "minecraft:raw_iron_block")
b.put(2, 2, 7, "minecraft:raw_iron_block")
b.put(1, 3, 7, "minecraft:raw_iron_block")
b.put(10, 2, 1, "minecraft:crafting_table")
b.put(9, 2, 1, "minecraft:smithing_table")
b.put(10, 2, 2, "minecraft:anvil[facing=east]")
b.put(10, 2, 7, "minecraft:iron_block")
b.put(9, 2, 7, "minecraft:iron_block")
b.put(10, 3, 7, "minecraft:iron_block")

# Lanterns on posts at the corners.
for x, z in ((0, 0), (W - 1, 0), (0, D - 1), (W - 1, D - 1)):
    b.put(x, 2, z, "minecraft:spruce_fence[east=false,north=false,south=false,waterlogged=false,west=false]")
    b.put(x, 3, z, "minecraft:lantern[hanging=false,waterlogged=false]")

b.save(sys.argv[1])
