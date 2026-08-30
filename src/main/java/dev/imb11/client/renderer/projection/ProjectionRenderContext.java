package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

public final class ProjectionRenderContext {
    private static final int MAX_FEED_RENDER_DISTANCE_CHUNKS = 6;
    private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();

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

    public static double cameraX(LevelRenderer renderer, Entity fallback) {
        State state = ACTIVE.get();
        return state != null && state.renderer == renderer ? state.camera.getPosition().x : fallback.getX();
    }

    public static double cameraY(LevelRenderer renderer, Entity fallback) {
        State state = ACTIVE.get();
        return state != null && state.renderer == renderer ? state.camera.getPosition().y : fallback.getY();
    }

    public static double cameraZ(LevelRenderer renderer, Entity fallback) {
        State state = ACTIVE.get();
        return state != null && state.renderer == renderer ? state.camera.getPosition().z : fallback.getZ();
    }

    public static int feedRenderDistance(int configuredDistance) {
        return Math.min(configuredDistance, MAX_FEED_RENDER_DISTANCE_CHUNKS);
    }

    public static int renderDistance(LevelRenderer renderer, int configuredDistance) {
        return isActiveRenderer(renderer) ? feedRenderDistance(configuredDistance) : configuredDistance;
    }

    public static Scope enter(LevelRenderer renderer, Camera camera, RenderTarget target, BlockPos hiddenTerrainBlock) {
        State previous = ACTIVE.get();
        ACTIVE.set(new State(renderer, camera, target, hiddenTerrainBlock));
        return new Scope(previous);
    }

    private record State(LevelRenderer renderer, Camera camera, RenderTarget target, BlockPos hiddenTerrainBlock) {
        private State {
            hiddenTerrainBlock = hiddenTerrainBlock == null ? null : hiddenTerrainBlock.immutable();
        }
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
