package dev.vellum.mod.mixin;

import dev.vellum.mod.client.VellumClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells Vellum when the player's statistics arrive (vanilla only tells its own statistics screen), so pages showing
 * them update. Runs on the client thread: the handler re-queues itself there before applying the packet.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleAwardStats", at = @At("TAIL"))
    private void vellum$statsUpdated(ClientboundAwardStatsPacket packet, CallbackInfo ci) {
        VellumClient.onStatsUpdated();
    }
}
