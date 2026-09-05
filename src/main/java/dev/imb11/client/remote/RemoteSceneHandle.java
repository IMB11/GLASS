package dev.imb11.client.remote;

import dev.imb11.sync.remote.RemoteSubscriptionId;
import dev.imb11.sync.remote.RemoteWorldState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

public final class RemoteSceneHandle implements AutoCloseable {
    private final RemoteSubscriptionId subscription;
    private final long localToken;
    private boolean released;

    RemoteSceneHandle(RemoteSubscriptionId subscription, long localToken) {
        this.subscription = subscription;
        this.localToken = localToken;
    }

    public RemoteSubscriptionId subscription() {
        return subscription;
    }

    public @Nullable ClientLevel level() {
        return RemoteSceneClientManager.level(this);
    }

    public @Nullable LightTexture lightTexture() {
        return RemoteSceneClientManager.lightTexture(this);
    }

    public @Nullable RemoteWorldState worldState() {
        return RemoteSceneClientManager.worldState(this);
    }

    public @Nullable ChunkPos grantedCenter() {
        return RemoteSceneClientManager.grantedCenter(this);
    }

    public int grantedRadius() {
        return RemoteSceneClientManager.grantedRadius(this);
    }

    public boolean isReady(ChunkPos cameraCenter) {
        return RemoteSceneClientManager.isReady(this, cameraCenter);
    }

    public boolean isLoading() {
        return RemoteSceneClientManager.isLoading(this);
    }

    public boolean isComplete() {
        return RemoteSceneClientManager.isComplete(this);
    }

    public boolean isUnavailable() {
        return RemoteSceneClientManager.isUnavailable(this);
    }

    public boolean isTerminalFailure() {
        return RemoteSceneClientManager.isTerminalFailure(this);
    }

    public String diagnostics() {
        return RemoteSceneClientManager.diagnostics(this);
    }

    boolean markReleased() {
        if (released) {
            return false;
        }
        released = true;
        return true;
    }

    boolean released() {
        return released;
    }

    long localToken() {
        return localToken;
    }

    @Override
    public void close() {
        RemoteSceneClientManager.release(this);
    }
}
