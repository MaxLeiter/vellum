package dev.vellum.mod.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-specific networking (META-INF/services). Payload types are registered by each loader's hooks from
 * {@code VellumNetwork}; common code only sends.
 */
public interface Network {
    /**
     * Sends a clientbound play payload to one player.
     *
     * @return false if the player's client cannot receive this payload (it lacks Vellum)
     */
    boolean sendToPlayer(ServerPlayer player, CustomPacketPayload payload);
}
