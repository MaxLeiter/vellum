package dev.vellum.engine.scene;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minecraft's binary NBT, as structure files hold it (gzip-compressed), read into plain Java values, and written back
 * as SNBT for the tags blocks carry. Tags become {@code Byte}, {@code Short}, {@code Integer}, {@code Long},
 * {@code Float}, {@code Double}, {@code String}, {@code byte[]}, {@code int[]}, {@code long[]}, {@code List} and
 * {@code Map} (in file order). Files come from resource packs and page folders, so reading is bounded: the
 * decompressed size, the nesting depth and every length are checked before anything is allocated for them.
 */
final class Nbt {
    /** The most a file may decompress to. */
    static final int MAX_BYTES = 8 << 20;
    /** Vanilla's limit on nesting. */
    private static final int MAX_DEPTH = 512;

    private final DataInputStream in;
    private long remaining;

    private Nbt(InputStream in, long limit) {
        this.in = new DataInputStream(in);
        this.remaining = limit;
    }

    /** The root compound of a gzip-compressed (or, failing that, uncompressed) NBT file. */
    static Map<String, Object> read(byte[] bytes) throws IOException {
        boolean gzip = bytes.length > 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
        InputStream raw = new ByteArrayInputStream(bytes);
        Nbt nbt = new Nbt(gzip ? new GZIPInputStream(raw) : raw, MAX_BYTES);
        if (nbt.u8() != 10) throw new IOException("not an NBT compound");
        nbt.string(); // the root's name
        return nbt.compound(0);
    }

    private Object value(int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested too deeply");
        return switch (type) {
            case 1 -> (byte) u8();
            case 2 -> { take(2); yield in.readShort(); }
            case 3 -> { take(4); yield in.readInt(); }
            case 4 -> { take(8); yield in.readLong(); }
            case 5 -> { take(4); yield in.readFloat(); }
            case 6 -> { take(8); yield in.readDouble(); }
            case 7 -> {
                byte[] a = new byte[length(1)];
                take(a.length);
                in.readFully(a);
                yield a;
            }
            case 8 -> string();
            case 9 -> list(depth);
            case 10 -> compound(depth);
            case 11 -> {
                int[] a = new int[length(4)];
                take(4L * a.length);
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                yield a;
            }
            case 12 -> {
                long[] a = new long[length(8)];
                take(8L * a.length);
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                yield a;
            }
            default -> throw new IOException("unknown NBT tag type " + type);
        };
    }

    private Map<String, Object> compound(int depth) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int type = u8(); type != 0; type = u8()) out.put(string(), value(type, depth + 1));
        return out;
    }

    private List<Object> list(int depth) throws IOException {
        int type = u8();
        int n = length(1);
        List<Object> out = new ArrayList<>(Math.min(n, 1024));
        for (int i = 0; i < n; i++) out.add(value(type, depth + 1));
        return out;
    }

    private String string() throws IOException {
        take(2);
        int n = in.readUnsignedShort();
        take(n);
        byte[] utf = new byte[n + 2];
        utf[0] = (byte) (n >> 8);
        utf[1] = (byte) n;
        in.readFully(utf, 2, n);
        return new DataInputStream(new ByteArrayInputStream(utf)).readUTF();
    }

    private int u8() throws IOException {
        take(1);
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    /**
     * A length prefix, checked against what is left, as each item takes at least {@code itemBytes}. Arrays, whose
     * items are read here, take their bytes at once; a list's items take theirs as they are read.
     */
    private int length(int itemBytes) throws IOException {
        take(4);
        int n = in.readInt();
        if (n < 0 || (long) n * Math.max(itemBytes, 1) > remaining) throw new IOException("NBT length out of range: " + n);
        return n;
    }

    private void take(long bytes) throws IOException {
        remaining -= bytes;
        if (remaining < 0) throw new IOException("NBT larger than " + MAX_BYTES + " bytes");
    }

    // ---- SNBT ----

    /** {@code value} as SNBT, as {@code /setblock} and {@code TagParser} read it. */
    static String snbt(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case Byte b -> out.append(b).append('b');
            case Short s -> out.append(s).append('s');
            case Integer i -> out.append(i);
            case Long l -> out.append(l).append('L');
            case Float f -> out.append(f).append('f');
            case Double d -> out.append(d).append('d');
            case String s -> quote(s, out);
            case byte[] a -> {
                out.append("[B;");
                for (int i = 0; i < a.length; i++) out.append(i == 0 ? "" : ",").append(a[i]).append('b');
                out.append(']');
            }
            case int[] a -> {
                out.append("[I;");
                for (int i = 0; i < a.length; i++) out.append(i == 0 ? "" : ",").append(a[i]);
                out.append(']');
            }
            case long[] a -> {
                out.append("[L;");
                for (int i = 0; i < a.length; i++) out.append(i == 0 ? "" : ",").append(a[i]).append('L');
                out.append(']');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(',');
                    write(list.get(i), out);
                }
                out.append(']');
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    quote(String.valueOf(e.getKey()), out);
                    out.append(':');
                    write(e.getValue(), out);
                }
                out.append('}');
            }
            default -> throw new IllegalArgumentException("not an NBT value: " + value);
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') out.append('\\');
            out.append(c);
        }
        out.append('"');
    }
}
