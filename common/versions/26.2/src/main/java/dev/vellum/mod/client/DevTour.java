package dev.vellum.mod.client;

import net.minecraft.client.Minecraft;

import java.util.Deque;

/** The scripted showcase tour for demo videos ({@code -Ptour}) is recorded on 26.x only; on 1.21.1 it is off. */
final class DevTour {
    static final boolean ENABLED = false;

    private DevTour() {}

    static void plan(Minecraft mc, Deque<Runnable> steps) {}
}
