package dev.imb11.mixins;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("entityMap")
    Int2ObjectMap<?> glass$getEntityMap();

    @Invoker("isChunkTracked")
    boolean glass$isChunkTracked(ServerPlayer player, int x, int z);

    @Invoker("getChunkToSend")
    LevelChunk glass$getChunkToSend(long chunk);
}
