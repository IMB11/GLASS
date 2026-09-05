package dev.imb11.mixins;

import dev.imb11.sync.remote.RemoteSceneServerManager;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "resendBiomesForChunks", at = @At("TAIL"))
    private void glass$remoteBiomesChanged(List<ChunkAccess> chunks, CallbackInfo ci) {
        RemoteSceneServerManager.onBiomesChanged(level, chunks);
    }
}
