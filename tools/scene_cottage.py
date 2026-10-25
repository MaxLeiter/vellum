#!/usr/bin/env python3
"""Writes the scene showcase's cottage as a structure file, as a structure block saves one.

    python3 tools/scene_cottage.py common/src/main/resources/assets/vellum/structures/cottage.nbt

The blocks carry their connection states (fences, panes, stairs), as a real save does.
"""
import gzip
import struct
import sys

DATA_VERSION = 4671  # any recent one: the scene reads states, not data fixes


def nbt_string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def tag(value):
    """(type id, payload) of a Python value: int, str, list of those, dict."""
    if isinstance(value, bool):
        return 1, struct.pack(">b", int(value))
    if isinstance(value, int):
        return 3, struct.pack(">i", value)
    if isinstance(value, str):
        return 8, nbt_string(value)
    if isinstance(value, list):
        if not value:
            return 9, struct.pack(">bi", 0, 0)
        kind = tag(value[0])[0]
        return 9, struct.pack(">bi", kind, len(value)) + b"".join(tag(v)[1] for v in value)
    if isinstance(value, dict):
        out = b""
        for key, v in value.items():
            kind, payload = tag(v)
            out += struct.pack(">b", kind) + nbt_string(key) + payload
        return 10, out + b"\0"
    raise TypeError(value)


blocks = {}


def put(x, y, z, state):
    blocks[(x, y, z)] = state


def fill(x1, y1, z1, x2, y2, z2, state):
    for x in range(x1, x2 + 1):
        for y in range(y1, y2 + 1):
            for z in range(z1, z2 + 1):
                put(x, y, z, state)


W, D = 14, 11  # ground, x 0..13 and z 0..10
# House walls x 2..8, z 2..7; the door is in the south wall (z 7), facing the path.
X0, X1, Z0, Z1 = 2, 8, 2, 7

# Ground, with a path to the door and a pond in the front garden.
fill(0, 0, 0, W - 1, 0, D - 1, "minecraft:grass_block[snowy=false]")
for z in range(Z1 + 1, D):
    put(5, 0, z, "minecraft:dirt_path")
fill(8, 0, 8, 9, 0, 9, "minecraft:water[level=0]")
put(10, 0, 9, "minecraft:sand")
put(8, 1, 9, "minecraft:lily_pad")

# Foundation: a cobblestone ring with a spruce floor.
fill(X0, 1, Z0, X1, 1, Z1, "minecraft:spruce_planks")
for x in range(X0, X1 + 1):
    put(x, 1, Z0, "minecraft:cobblestone")
    put(x, 1, Z1, "minecraft:cobblestone")
for z in range(Z0, Z1 + 1):
    put(X0, 1, z, "minecraft:cobblestone")
    put(X1, 1, z, "minecraft:cobblestone")
put(5, 1, Z1 + 1, "minecraft:cobblestone_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")

# Walls: log corners, plank walls, a door and pane windows.
for y in range(2, 5):
    for x in range(X0, X1 + 1):
        put(x, y, Z0, "minecraft:oak_planks")
        put(x, y, Z1, "minecraft:oak_planks")
    for z in range(Z0, Z1 + 1):
        put(X0, y, z, "minecraft:oak_planks")
        put(X1, y, z, "minecraft:oak_planks")
    for x, z in ((X0, Z0), (X0, Z1), (X1, Z0), (X1, Z1)):
        put(x, y, z, "minecraft:oak_log[axis=y]")
door = "minecraft:oak_door[facing=south,half={},hinge=left,open=false,powered=false]"
put(5, 2, Z1, door.format("lower"))
put(5, 3, Z1, door.format("upper"))
pane_x = "minecraft:glass_pane[east=true,north=false,south=false,waterlogged=false,west=true]"
pane_z = "minecraft:glass_pane[east=false,north=true,south=true,waterlogged=false,west=false]"
for x in (3, 7):
    put(x, 3, Z1, pane_x)
put(4, 3, Z0, pane_x)
put(5, 3, Z0, pane_x)
put(6, 3, Z0, pane_x)
for z in (4, 5):
    put(X0, 3, z, pane_z)
    put(X1, 3, z, pane_z)
lantern = "minecraft:lantern[hanging=false,waterlogged=false]"
put(4, 4, Z1 + 1, "minecraft:lantern[hanging=true,waterlogged=false]")

# Inside: a furnace, a crafting table with a lantern, a chest, a bed and a barrel.
put(3, 2, 3, "minecraft:furnace[facing=south,lit=true]")
put(4, 2, 3, "minecraft:crafting_table")
put(4, 3, 3, lantern)
put(7, 2, 3, "minecraft:chest[facing=south,type=single,waterlogged=false]")
put(7, 2, 5, "minecraft:red_bed[facing=north,occupied=false,part=head]")
put(7, 2, 6, "minecraft:red_bed[facing=north,occupied=false,part=foot]")
put(3, 2, 6, "minecraft:barrel[facing=up,open=false]")
put(3, 3, 6, "minecraft:potted_red_tulip")

# Wall plate and gables, under a spruce roof that overhangs a block all round.
for x in range(X0, X1 + 1):
    put(x, 5, Z0, "minecraft:stripped_oak_log[axis=x]")
    put(x, 5, Z1, "minecraft:stripped_oak_log[axis=x]")
for x in (X0, X1):
    for z in range(Z0 + 1, Z1):
        put(x, 5, z, "minecraft:oak_planks")
    for z in range(Z0 + 1, Z1):
        put(x, 6, z, "minecraft:oak_planks")
    for z in range(Z0 + 2, Z1 - 1):
        put(x, 7, z, "minecraft:oak_planks")
put(X0, 6, 4, "minecraft:glass_pane[east=false,north=true,south=true,waterlogged=false,west=false]")
put(X0, 6, 5, "minecraft:glass_pane[east=false,north=true,south=true,waterlogged=false,west=false]")
stairs = "minecraft:spruce_stairs[facing={},half=bottom,shape=straight,waterlogged=false]"
for x in range(X0 - 1, X1 + 2):
    for i in range(4):
        put(x, 5 + i, Z0 - 1 + i, stairs.format("south"))
        put(x, 5 + i, Z1 + 1 - i, stairs.format("north"))
# A chimney through the back of the roof.
for y in range(5, 10):
    put(7, y, 3, "minecraft:cobblestone")
put(7, 10, 3, "minecraft:cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]")

# The front fence, with a gate on the path.
fence = "minecraft:oak_fence[east={},north=false,south=false,waterlogged=false,west={}]"
for x in range(0, W):
    if x == 5:
        put(x, 1, D - 1, "minecraft:oak_fence_gate[facing=south,in_wall=false,open=false,powered=false]")
        continue
    east = "true" if x < W - 1 else "false"
    west = "true" if x > 0 else "false"
    put(x, 1, D - 1, fence.format(east, west))
for x, flower in ((1, "poppy"), (2, "dandelion"), (3, "cornflower"), (7, "oxeye_daisy"), (6, "azure_bluet")):
    put(x, 1, D - 2, "minecraft:" + flower)
put(1, 1, 8, "minecraft:short_grass")
put(12, 1, 8, "minecraft:short_grass")
put(10, 1, 8, "minecraft:fern")

# A tree beside the house.
TX, TZ = 12, 4
for y in range(1, 6):
    put(TX, y, TZ, "minecraft:oak_log[axis=y]")
leaves = "minecraft:oak_leaves[distance={},persistent=true,waterlogged=false]"
for y, r in ((4, 2), (5, 2), (6, 1), (7, 1)):
    for x in range(TX - r, TX + r + 1):
        for z in range(TZ - r, TZ + r + 1):
            if not (0 <= x < W and 0 <= z < D) or (x, y, z) in blocks:
                continue
            if r == 2 and abs(x - TX) == 2 and abs(z - TZ) == 2:
                continue
            if y == 7 and abs(x - TX) + abs(z - TZ) > 1:
                continue
            put(x, y, z, leaves.format(max(1, abs(x - TX) + abs(z - TZ))))

palette, index, entries = [], {}, []
for (x, y, z), state in sorted(blocks.items(), key=lambda e: (e[0][1], e[0][2], e[0][0])):
    if state not in index:
        index[state] = len(palette)
        name, _, props = state.partition("[")
        entry = {"Name": name}
        if props:
            entry["Properties"] = dict(p.split("=") for p in props.rstrip("]").split(","))
        palette.append(entry)
    entries.append({"pos": [x, y, z], "state": index[state]})

size = [max(p[i] for p in blocks) + 1 for i in range(3)]
root = {"DataVersion": DATA_VERSION, "size": size, "palette": palette, "blocks": entries, "entities": []}
kind, payload = tag(root)
data = struct.pack(">b", kind) + nbt_string("") + payload
with open(sys.argv[1], "wb") as f:
    f.write(gzip.compress(data, mtime=0))
print(f"{len(entries)} blocks, {len(palette)} states, size {size}")
