package dev.imb11.mixins;

import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import dev.imb11.client.remote.ProjectionChunkAccess;
import dev.imb11.client.remote.ProjectionChunkStorage;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheMixin implements ProjectionChunkAccess {
    @Shadow @Final private ClientLevel level;
    @Unique private final ProjectionChunkStorage glass$chunks = new ProjectionChunkStorage((ClientChunkCache) (Object) this);

    @Override
    public ProjectionChunkStorage glass$projectionChunks() {
        return glass$chunks;
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("HEAD"), cancellable = true)
    private void glass$getRetainedChunk(int x, int z, ChunkStatus status, boolean load, CallbackInfoReturnable<LevelChunk> cir) {
        LevelChunk chunk = glass$chunks.get(x, z);
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }

    @Inject(method = "replaceWithPacketData", at = @At("HEAD"), cancellable = true)
    private void glass$updateRetainedChunk(int x, int z, FriendlyByteBuf data, CompoundTag heightmaps,
                                         Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities,
                                         CallbackInfoReturnable<LevelChunk> cir) {
        if (glass$chunks.contains(x, z)) {
            cir.setReturnValue(glass$chunks.replace(level, x, z, data, heightmaps, blockEntities, true));
        }
    }

    @Inject(method = "drop", at = @At("HEAD"))
    private void glass$retainProjectionChunk(ChunkPos pos, CallbackInfo ci) {
        glass$chunks.dropVanilla(level, pos);
    }

    @Inject(method = "replaceBiomes", at = @At("HEAD"), cancellable = true)
    private void glass$updateRetainedBiomes(int x, int z, FriendlyByteBuf data, CallbackInfo ci) {
        LevelChunk chunk = glass$chunks.get(x, z);
        if (chunk != null) {
            chunk.replaceBiomes(data);
            ci.cancel();
        }
    }

    @Inject(method = "onLightUpdate", at = @At("HEAD"), cancellable = true)
    private void glass$routeRemoteLightUpdate(LightLayer layer, SectionPos sectionPos, CallbackInfo callbackInfo) {
        if (ProjectionRenderContext.routeRemoteLightUpdate(
                (ClientChunkCache) (Object) this,
                layer,
                sectionPos
        )) {
            callbackInfo.cancel();
        }
    }
}
