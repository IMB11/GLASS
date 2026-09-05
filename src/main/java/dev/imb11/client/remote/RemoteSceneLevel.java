package dev.imb11.client.remote;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.function.Supplier;

final class RemoteSceneLevel extends ClientLevel {
    RemoteSceneLevel(ClientPacketListener connection, ClientLevelData data, ResourceKey<Level> dimension,
                     Holder<DimensionType> type, int viewDistance, Supplier<ProfilerFiller> profiler,
                     LevelRenderer renderer, boolean debug, long seed) {
        super(connection, data, dimension, type, viewDistance, 0, profiler, renderer, debug, seed);
    }

    void tickVisualBlockEntities() {
        tickBlockEntities();
    }

    @Override
    public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
    }

    @Override
    public void addParticle(ParticleOptions particle, boolean force, double x, double y, double z, double dx, double dy, double dz) {
    }

    @Override
    public void addAlwaysVisibleParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
    }

    @Override
    public void addAlwaysVisibleParticle(ParticleOptions particle, boolean force, double x, double y, double z, double dx, double dy, double dz) {
    }

    @Override
    public void playLocalSound(double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch, boolean delay) {
    }

    @Override
    public void playLocalSound(Entity entity, SoundEvent sound, SoundSource source, float volume, float pitch) {
    }
}
