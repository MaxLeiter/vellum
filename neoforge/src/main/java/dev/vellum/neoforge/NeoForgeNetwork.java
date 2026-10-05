package dev.vellum.neoforge;

import dev.vellum.mod.platform.Network;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

public final class NeoForgeNetwork implements Network {
    @Override
    public boolean sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (!player.connection.hasChannel(payload)) return false;
        PacketDistributor.sendToPlayer(player, payload);
        return true;
    }
}
