package dev.imb11.mixins;

import dev.imb11.sync.remote.RemoteSceneServerManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Inject(method = "blockChanged", at = @At("TAIL"))
    private void glass$remoteBlockChanged(BlockPos pos, CallbackInfo ci) {
        RemoteSceneServerManager.onBlockChanged((ServerLevel) ((ServerChunkCache) (Object) this).getLevel(), pos);
    }

    @Inject(method = "onLightUpdate", at = @At("TAIL"))
    private void glass$remoteLightChanged(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        RemoteSceneServerManager.onLightChanged((ServerLevel) ((ServerChunkCache) (Object) this).getLevel(), layer, pos);
    }
}
