package dev.imb11.client.compat;

import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteContentsExtension;
import dev.imb11.platform.Platform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

public final class SodiumCompatibility {
    private static final boolean LOADED = Platform.isModLoaded("sodium");

    private SodiumCompatibility() {
    }

    public static void onChunkLoaded(ClientLevel level, ChunkPos pos) {
        if (LOADED) {
            SodiumAccess.loaded(level, pos);
        }
    }

    public static void onChunkUnloaded(ClientLevel level, ChunkPos pos) {
        if (LOADED) {
            SodiumAccess.unloaded(level, pos);
        }
    }

    public static void activateProjectionTextures(List<SpriteContents> sprites) {
        if (LOADED && ProjectionRenderManager.hasVisibleProjection()) {
            SodiumAccess.activateTextures(sprites);
        }
    }

    private static final class SodiumAccess {
        private static void loaded(ClientLevel level, ChunkPos pos) {
            ChunkTrackerHolder.get(level).onChunkStatusAdded(pos.x, pos.z, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        }

        private static void unloaded(ClientLevel level, ChunkPos pos) {
            ChunkTrackerHolder.get(level).onChunkStatusRemoved(pos.x, pos.z, ChunkStatus.FLAG_ALL);
        }

        private static void activateTextures(List<SpriteContents> sprites) {
            for (SpriteContents sprite : sprites) {
                if (sprite instanceof SpriteContentsExtension extension && extension.sodium$hasAnimation()) {
                    extension.sodium$setActive(true);
                }
            }
        }
    }
}
