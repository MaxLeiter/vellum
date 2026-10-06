package com.example.examplemod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.vellum.mod.client.VellumScreen;
import dev.vellum.mod.client.VellumScreens;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** The notes page: opens assets/examplemod/vellum/notes.html with the notes, and handles what it sends back. */
final class Notes {
    private static final String PAGE = "examplemod:vellum/notes.html";
    // Kept in memory to keep the example short. A real mod would save them.
    private static final List<String> NOTES = new ArrayList<>();

    private Notes() {}

    static void open() {
        VellumScreen screen = VellumScreens.open(PAGE, data());
        screen.driver()
                .onMessage("add", value -> {
                    NOTES.add(value.getAsString());
                    screen.driver().push(data());
                })
                .onMessage("remove", value -> {
                    int i = value.getAsInt();
                    if (i >= 0 && i < NOTES.size()) NOTES.remove(i);
                    screen.driver().push(data());
                });
    }

    /** What the page sees as vellum.data. */
    private static JsonObject data() {
        JsonObject data = new JsonObject();
        data.addProperty("player", Minecraft.getInstance().getUser().getName());
        JsonArray notes = new JsonArray();
        NOTES.forEach(notes::add);
        data.add("notes", notes);
        return data;
    }
}
