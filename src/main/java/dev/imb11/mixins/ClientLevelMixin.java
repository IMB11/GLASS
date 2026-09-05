package dev.imb11.mixins;

import dev.imb11.client.compat.SodiumCompatibility;
import dev.imb11.client.remote.ProjectionChunkStorage;
import dev.imb11.client.remote.RemoteSceneClientManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "unload", at = @At("HEAD"), cancellable = true)
    private void glass$retainChunk(LevelChunk chunk, CallbackInfo ci) {
        if (ProjectionChunkStorage.of((ClientLevel) (Object) this).protectFromUnload(chunk)) {
            SodiumCompatibility.onChunkUnloaded((ClientLevel) (Object) this, chunk.getPos());
            ci.cancel();
        }
    }

    @Inject(method = "addEntity", at = @At("HEAD"))
    private void glass$nativeEntityAdded(Entity entity, CallbackInfo ci) {
        RemoteSceneClientManager.onVanillaEntityAdded((ClientLevel) (Object) this, entity);
    }

    @Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true)
    private void glass$retainEntity(int id, Entity.RemovalReason reason, CallbackInfo ci) {
        if (RemoteSceneClientManager.onVanillaEntityRemoved((ClientLevel) (Object) this, id)) {
            ci.cancel();
        }
    }
}
