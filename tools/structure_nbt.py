"""Writes structure files as a structure block saves them, for the scene showcases' generators.

    from structure_nbt import Build
    b = Build()
    b.fill(0, 0, 0, 4, 0, 4, "minecraft:stone")
    b.put(2, 1, 2, "minecraft:furnace[facing=south,lit=true]")
    b.save(sys.argv[1])

States are written as /setblock takes them, properties and all, and saved with them. Connected blocks (fences,
panes, walls, stairs) need their connections written out, as a real save has them.
"""
import gzip
import struct

DATA_VERSION = 4671  # any recent one: the scene reads states, not data fixes


def _string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def _tag(value):
    """(type id, payload) of a Python value: bool, int, str, list of one of those, dict."""
    if isinstance(value, bool):
        return 1, struct.pack(">b", int(value))
    if isinstance(value, int):
        return 3, struct.pack(">i", value)
    if isinstance(value, str):
        return 8, _string(value)
    if isinstance(value, list):
        if not value:
            return 9, struct.pack(">bi", 0, 0)
        kind = _tag(value[0])[0]
        return 9, struct.pack(">bi", kind, len(value)) + b"".join(_tag(v)[1] for v in value)
    if isinstance(value, dict):
        out = b""
        for key, v in value.items():
            kind, payload = _tag(v)
            out += struct.pack(">b", kind) + _string(key) + payload
        return 10, out + b"\0"
    raise TypeError(value)


class Build:
    def __init__(self):
        self.blocks = {}

    def put(self, x, y, z, state):
        if x < 0 or y < 0 or z < 0:
            raise ValueError(f"negative position {x} {y} {z}: structures start at 0 0 0")
        if state == "minecraft:air":
            self.blocks.pop((x, y, z), None)
        else:
            self.blocks[(x, y, z)] = state

    def fill(self, x1, y1, z1, x2, y2, z2, state):
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for y in range(min(y1, y2), max(y1, y2) + 1):
                for z in range(min(z1, z2), max(z1, z2) + 1):
                    self.put(x, y, z, state)

    def get(self, x, y, z):
        return self.blocks.get((x, y, z))

    def hollow(self, solid):
        """Drops the blocks no one can see: those whose six neighbours all pass solid(state)."""
        hidden = [p for p, state in self.blocks.items() if solid(state) and all(
            solid(self.blocks.get((p[0] + dx, p[1] + dy, p[2] + dz), "minecraft:air"))
            for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)))]
        for p in hidden:
            del self.blocks[p]

    def save(self, path):
        palette, index, entries = [], {}, []
        for (x, y, z), state in sorted(self.blocks.items(), key=lambda e: (e[0][1], e[0][2], e[0][0])):
            if state not in index:
                index[state] = len(palette)
                name, _, props = state.partition("[")
                entry = {"Name": name}
                if props:
                    entry["Properties"] = dict(p.split("=") for p in props.rstrip("]").split(","))
                palette.append(entry)
            entries.append({"pos": [x, y, z], "state": index[state]})
        size = [max(p[i] for p in self.blocks) + 1 for i in range(3)]
        root = {"DataVersion": DATA_VERSION, "size": size, "palette": palette, "blocks": entries, "entities": []}
        kind, payload = _tag(root)
        with open(path, "wb") as f:
            f.write(gzip.compress(struct.pack(">b", kind) + _string("") + payload, mtime=0))
        print(f"{len(entries)} blocks, {len(palette)} states, size {size}")
