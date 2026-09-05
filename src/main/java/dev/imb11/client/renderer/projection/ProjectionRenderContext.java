package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.imb11.projection.ProjectionChunkRegion;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;

import java.util.IdentityHashMap;
import java.util.Map;

public final class ProjectionRenderContext {
    private static final int MAX_FEED_RENDER_DISTANCE_CHUNKS = 6;
    private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();
    private static final Map<ClientChunkCache, LightUpdateListener> REMOTE_LIGHT_UPDATES = new IdentityHashMap<>();

    private ProjectionRenderContext() {
    }

    public static boolean isActive() {
        return ACTIVE.get() != null;
    }

    public static boolean isActiveRenderer(LevelRenderer renderer) {
        State state = ACTIVE.get();
        return state != null && state.renderer == renderer;
    }

    public static RenderTarget target() {
        State state = ACTIVE.get();
        return state == null ? null : state.target;
    }

    public static BlockPos hiddenTerrainBlock() {
        State state = ACTIVE.get();
        return state == null ? null : state.hiddenTerrainBlock;
    }

    public static ClientLevel level(ClientLevel fallback) {
        State state = ACTIVE.get();
        return state == null || state.level == null ? fallback : state.level;
    }

    public static ClientLevel level(LevelRenderer renderer, ClientLevel fallback) {
        State state = ACTIVE.get();
        return state == null || state.renderer != renderer || state.level == null ? fallback : state.level;
    }

    public static Camera camera(LevelRenderer renderer, Camera fallback) {
        State state = ACTIVE.get();
        return state != null && state.renderer == renderer ? state.camera : fallback;
    }

    public static LightTexture lightTexture() {
        State state = ACTIVE.get();
        return state == null ? null : state.lightTexture;
    }

    public static int grantedRenderDistance() {
        State state = ACTIVE.get();
        return state == null ? MAX_FEED_RENDER_DISTANCE_CHUNKS : state.grantedRenderDistanceChunks;
    }

    public static double cameraX(LevelRenderer renderer, Entity fallback) {
        State state = ACTIVE.get();
        return state != null && state.renderer == renderer
                ? (state.camera instanceof ProjectionCamera camera ? camera.gridCenter().getMiddleBlockX()
                : ProjectionChunkRegion.gridAnchor(state.camera.getPosition().x)) : fallback.getX();
    }

    public static double cameraY(LevelRenderer renderer, Entity fallback) {
        State state = ACTIVE.get();
        return state != null && state.renderer == renderer ? state.camera.getPosition().y : fallback.getY();
    }

    public static double cameraZ(LevelRenderer renderer, Entity fallback) {
        State state = ACTIVE.get();
        return state != null && state.renderer == renderer
                ? (state.camera instanceof ProjectionCamera camera ? camera.gridCenter().getMiddleBlockZ()
                : ProjectionChunkRegion.gridAnchor(state.camera.getPosition().z)) : fallback.getZ();
    }

    public static int feedRenderDistance(int configuredDistance) {
        return Math.min(configuredDistance, MAX_FEED_RENDER_DISTANCE_CHUNKS);
    }

    public static int feedRenderDistance(int configuredDistance, int grantedDistance) {
        return Math.min(configuredDistance, Math.min(MAX_FEED_RENDER_DISTANCE_CHUNKS, Math.max(1, grantedDistance)));
    }

    public static int renderDistance(LevelRenderer renderer, int configuredDistance) {
        State state = ACTIVE.get();
        return state != null && state.renderer == renderer
                ? feedRenderDistance(configuredDistance, state.grantedRenderDistanceChunks)
                : configuredDistance;
    }

    public static Scope enter(LevelRenderer renderer, Camera camera, RenderTarget target, BlockPos hiddenTerrainBlock) {
        return enter(
                renderer,
                camera,
                target,
                hiddenTerrainBlock,
                null,
                null,
                MAX_FEED_RENDER_DISTANCE_CHUNKS
        );
    }

    public static Scope enter(
            LevelRenderer renderer,
            Camera camera,
            RenderTarget target,
            BlockPos hiddenTerrainBlock,
            ClientLevel level,
            LightTexture lightTexture,
            int grantedRenderDistanceChunks
    ) {
        State previous = ACTIVE.get();
        ACTIVE.set(new State(
                renderer,
                camera,
                target,
                hiddenTerrainBlock,
                level,
                lightTexture,
                grantedRenderDistanceChunks
        ));
        return new Scope(previous);
    }

    public static void registerRemoteLightUpdates(ClientChunkCache chunkCache, LightUpdateListener listener) {
        synchronized (REMOTE_LIGHT_UPDATES) {
            REMOTE_LIGHT_UPDATES.put(chunkCache, listener);
        }
    }

    public static void unregisterRemoteLightUpdates(ClientChunkCache chunkCache) {
        synchronized (REMOTE_LIGHT_UPDATES) {
            REMOTE_LIGHT_UPDATES.remove(chunkCache);
        }
    }

    public static void clearRemoteLightUpdates() {
        synchronized (REMOTE_LIGHT_UPDATES) {
            REMOTE_LIGHT_UPDATES.clear();
        }
    }

    public static boolean routeRemoteLightUpdate(ClientChunkCache chunkCache, LightLayer layer, SectionPos sectionPos) {
        LightUpdateListener listener;
        synchronized (REMOTE_LIGHT_UPDATES) {
            listener = REMOTE_LIGHT_UPDATES.get(chunkCache);
        }
        if (listener == null) {
            return false;
        }
        listener.onLightUpdate(layer, sectionPos);
        return true;
    }

    private record State(
            LevelRenderer renderer,
            Camera camera,
            RenderTarget target,
            BlockPos hiddenTerrainBlock,
            ClientLevel level,
            LightTexture lightTexture,
            int grantedRenderDistanceChunks
    ) {
        private State {
            hiddenTerrainBlock = hiddenTerrainBlock == null ? null : hiddenTerrainBlock.immutable();
            grantedRenderDistanceChunks = Math.min(
                    MAX_FEED_RENDER_DISTANCE_CHUNKS,
                    Math.max(1, grantedRenderDistanceChunks)
            );
        }
    }

    @FunctionalInterface
    public interface LightUpdateListener {
        void onLightUpdate(LightLayer layer, SectionPos sectionPos);
    }

    public static final class Scope implements AutoCloseable {
        private final State previous;
        private boolean closed;

        private Scope(State previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }

            closed = true;
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }
}
