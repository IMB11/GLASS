package dev.imb11.mixins;

import dev.imb11.client.remote.ProjectionChunkStorage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Redirect(method = "queueLightRemoval", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;queueLightUpdate(Ljava/lang/Runnable;)V"))
    private void glass$retainProjectionLight(ClientLevel level, Runnable removal, ClientboundForgetLevelChunkPacket packet) {
        level.queueLightUpdate(() -> {
            if (!ProjectionChunkStorage.of(level).retained(packet.pos())) {
                removal.run();
            }
        });
    }
}
