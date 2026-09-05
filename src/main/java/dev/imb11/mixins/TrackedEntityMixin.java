package dev.imb11.mixins;

import dev.imb11.sync.remote.RemoteSceneServerManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityMixin {
    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "broadcast", at = @At("TAIL"))
    private void glass$broadcastRemoteEntity(Packet<?> packet, CallbackInfo ci) {
        RemoteSceneServerManager.onEntityPacket(entity, packet);
    }

    @Inject(method = "broadcastRemoved", at = @At("TAIL"))
    private void glass$removeRemoteEntity(CallbackInfo ci) {
        RemoteSceneServerManager.onEntityRemoved(entity);
    }
}
