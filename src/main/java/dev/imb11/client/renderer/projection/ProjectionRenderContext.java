package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;

public final class ProjectionRenderContext {
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

    public static double cameraX(LevelRenderer renderer, double fallback) {
        State state = ACTIVE.get();
        return state != null && state.renderer == renderer ? state.camera.getPosition().x : fallback;
    }

    public static double cameraY(LevelRenderer renderer, double fallback) {
        State state = ACTIVE.get();
        return state != null && state.renderer == renderer ? state.camera.getPosition().y : fallback;
    }

    public static double cameraZ(LevelRenderer renderer, double fallback) {
        State state = ACTIVE.get();
        return state != null && state.renderer == renderer ? state.camera.getPosition().z : fallback;
    }

    public static Scope enter(LevelRenderer renderer, Camera camera, RenderTarget target) {
        State previous = ACTIVE.get();
        ACTIVE.set(new State(renderer, camera, target));
        return new Scope(previous);
    }

    private record State(LevelRenderer renderer, Camera camera, RenderTarget target) {
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
