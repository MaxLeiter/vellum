package dev.vellum.mod.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;

/**
 * Checks for JSON that crosses the network: how deeply it nests, and a strict parse. Deep nesting would overflow the
 * stack of a recursive parser or of code that walks the value ({@code JsonElement.toString}, a handler's own
 * recursion), so it is refused before anything parses it.
 */
public final class JsonLimits {
    private static final Gson GSON = new Gson();

    private JsonLimits() {}

    /** How deeply arrays and objects nest in {@code json} (0 for a bare value), skipping strings. Linear, no recursion. */
    public static int depth(String json) {
        int depth = 0, max = 0;
        boolean string = false;
        for (int i = 0, n = json.length(); i < n; i++) {
            char c = json.charAt(i);
            if (string) {
                if (c == '\\') i++;
                else if (c == '"') string = false;
                continue;
            }
            switch (c) {
                case '"' -> string = true;
                case '[', '{' -> max = Math.max(max, ++depth);
                case ']', '}' -> depth--;
                default -> { }
            }
        }
        return max;
    }

    /**
     * Parses one JSON value strictly (no NaN or Infinity, no comments, unquoted names or trailing text), after checking
     * that it nests at most {@code maxDepth} deep.
     *
     * @throws JsonParseException when the text is too deep or is not one JSON value
     */
    public static JsonElement parse(String json, int maxDepth) {
        if (depth(json) > maxDepth) throw new JsonParseException("JSON nests deeper than " + maxDepth);
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            JsonElement value = GSON.getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new JsonParseException("Text after the JSON value");
            return value;
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            throw new JsonParseException(e.getMessage(), e);
        }
    }
}
