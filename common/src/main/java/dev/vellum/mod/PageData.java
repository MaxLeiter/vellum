package dev.vellum.mod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A page's {@code vellum.data} as JSON text, kept for its next load by {@code DocumentDriver}, a HUD overlay and a
 * server page waiting to show. An object is kept as the text of each of its fields, so setting one ({@link #put})
 * serialises only that field's value, and the page's next load joins the texts rather than walking a tree. Nothing
 * the mod passes in is kept, so changing it afterwards changes nothing here. Data that is not an object, and data a
 * server sent, stay one text until a field is set: then they are split once (an object) or replaced by a new object
 * (anything else). Not thread-safe.
 */
public final class PageData {
    /** The data as one text, or null when there is none or it is kept as {@link #fields} and not joined yet. */
    private @Nullable String json;
    /** For an object, each field's {@code "name":value} text by name, in order; else null. */
    private @Nullable Map<String, String> fields;
    /** The total length of the {@link #fields} texts. */
    private int entryChars;

    /** Replaces the data with {@code json} (a JSON value), or with none. */
    public void set(@Nullable String json) {
        this.json = json;
        fields = null;
    }

    /** No data. */
    public void clear() {
        set((String) null);
    }

    /** Replaces the data with {@code value}. */
    public void set(JsonElement value) {
        if (value instanceof JsonObject object) {
            fields = new LinkedHashMap<>();
            entryChars = 0;
            json = null;
            for (Map.Entry<String, JsonElement> field : object.entrySet()) put(field.getKey(), field.getValue().toString());
        } else {
            set(value.toString());
        }
    }

    /** Takes {@code other}'s data (texts are immutable, so this copies only the field map). */
    public void set(PageData other) {
        json = other.json;
        fields = other.fields == null ? null : new LinkedHashMap<>(other.fields);
        entryChars = other.entryChars;
    }

    /**
     * Sets the top-level field {@code key} to {@code value}, or removes it for null or JSON null. Returns the value's
     * JSON text, or null when the field was removed.
     */
    public @Nullable String put(String key, @Nullable JsonElement value) {
        String text = value == null || value.isJsonNull() ? null : value.toString();
        put(key, text);
        return text;
    }

    /**
     * Sets the top-level field {@code key} to {@code valueJson} (a JSON value), or removes it when Java null (the text
     * {@code "null"} sets the field to null).
     */
    public void put(String key, @Nullable String valueJson) {
        Map<String, String> map = fields();
        String entry = valueJson == null ? null : entry(key, valueJson);
        String old = entry == null ? map.remove(key) : map.put(key, entry);
        entryChars += (entry == null ? 0 : entry.length()) - (old == null ? 0 : old.length());
        json = null;
    }

    /** Sets the top-level fields {@code fields} has and keeps the others. */
    public void merge(JsonObject fields) {
        for (Map.Entry<String, JsonElement> field : fields.entrySet()) put(field.getKey(), field.getValue().toString());
    }

    /** The data as JSON text, or null when there is none. Joined when it was changed since last asked for. */
    public @Nullable String json() {
        if (json == null && fields != null) {
            StringBuilder sb = new StringBuilder(joinedChars(fields.size(), entryChars)).append('{');
            for (String entry : fields.values()) {
                if (sb.length() > 1) sb.append(',');
                sb.append(entry);
            }
            json = sb.append('}').toString();
        }
        return json;
    }

    /**
     * The length of {@link #json()} with field {@code key} set to a value of {@code valueChars} characters, without
     * joining the fields (the first time, data kept as one text is split).
     */
    public int charsWith(String key, int valueChars) {
        Map<String, String> map = fields();
        String old = map.get(key);
        int chars = entryChars + quote(key).length() + 1 + valueChars;
        return old != null ? joinedChars(map.size(), chars - old.length()) : joinedChars(map.size() + 1, chars);
    }

    /** {@code {}}, {@code count} fields of {@code chars} in all, and the commas between them. */
    private static int joinedChars(int count, int chars) {
        return 2 + chars + Math.max(0, count - 1);
    }

    /** The fields, splitting data kept as one text first: an object's fields, or none for anything else. */
    private Map<String, String> fields() {
        if (fields != null) return fields;
        JsonElement value = null;
        if (json != null) {
            try {
                value = JsonParser.parseString(json);
            } catch (JsonParseException e) {
                // Malformed data from a server: the page couldn't parse it either, so start from an empty object.
            }
        }
        if (value instanceof JsonObject object) {
            set(object);
        } else {
            fields = new LinkedHashMap<>();
            entryChars = 0;
        }
        return fields;
    }

    private static String entry(String key, String valueJson) {
        return quote(key) + ":" + valueJson;
    }

    private static String quote(String key) {
        return new JsonPrimitive(key).toString();
    }
}
