package dev.vellum.engine.scene;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Writes the values {@link Nbt} reads as a gzip-compressed NBT file, for tests. */
final class NbtWriter {
    private NbtWriter() {}

    static byte[] gzip(Map<String, Object> root) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
                out.writeByte(10);
                out.writeUTF("");
                compound(root, out);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int type(Object v) {
        return switch (v) {
            case Byte b -> 1;
            case Short s -> 2;
            case Integer i -> 3;
            case Long l -> 4;
            case Float f -> 5;
            case Double d -> 6;
            case byte[] a -> 7;
            case String s -> 8;
            case List<?> l -> 9;
            case Map<?, ?> m -> 10;
            case int[] a -> 11;
            case long[] a -> 12;
            default -> throw new IllegalArgumentException(String.valueOf(v));
        };
    }

    private static void value(Object v, DataOutputStream out) throws IOException {
        switch (v) {
            case Byte b -> out.writeByte(b);
            case Short s -> out.writeShort(s);
            case Integer i -> out.writeInt(i);
            case Long l -> out.writeLong(l);
            case Float f -> out.writeFloat(f);
            case Double d -> out.writeDouble(d);
            case byte[] a -> {
                out.writeInt(a.length);
                out.write(a);
            }
            case String s -> out.writeUTF(s);
            case List<?> l -> {
                out.writeByte(l.isEmpty() ? 0 : type(l.get(0)));
                out.writeInt(l.size());
                for (Object item : l) value(item, out);
            }
            case Map<?, ?> m -> compound(m, out);
            case int[] a -> {
                out.writeInt(a.length);
                for (int i : a) out.writeInt(i);
            }
            case long[] a -> {
                out.writeInt(a.length);
                for (long l : a) out.writeLong(l);
            }
            default -> throw new IllegalArgumentException(String.valueOf(v));
        }
    }

    private static void compound(Map<?, ?> m, DataOutputStream out) throws IOException {
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.writeByte(type(e.getValue()));
            out.writeUTF(String.valueOf(e.getKey()));
            value(e.getValue(), out);
        }
        out.writeByte(0);
    }
}
