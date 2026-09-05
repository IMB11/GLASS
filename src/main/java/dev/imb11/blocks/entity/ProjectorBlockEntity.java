package dev.imb11.blocks.entity;

import com.mojang.logging.LogUtils;
import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.ProjectorBlock;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.projection.ProjectionSurface;
import dev.imb11.sounds.GSounds;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Objects;

public class ProjectorBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final float BEACON_MAX_SPEED = 144.0F;
    private static final float BEACON_RAMP_TICKS = 80.0F;
    public static BlockEntityType<ProjectorBlockEntity> BLOCK_ENTITY_TYPE = FabricBlockEntityTypeBuilder.create(ProjectorBlockEntity::new, GBlocks.PROJECTOR).build();
    public boolean active = false;
    private String channel = "";
    private float rotationBeacon;
    private float rotationBeaconPrev;
    private float beaconSpinProgress;
    private float beaconSpeed;
    private float beaconSpeedPrev;
    private boolean beaconAnimationInitialized;

    private ProjectionSurface projectionSurface;
    private long projectionSurfaceVersion;
    private int revealDistance = -1;
    private int clientRevealDistance = -1;
    private boolean clientProjectionReady;
    private long lastPreparationTick = Long.MIN_VALUE;

    public ProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(BLOCK_ENTITY_TYPE, pos, state);
    }

    public static void tick(Level world, BlockPos pos, BlockState state, ProjectorBlockEntity be) {
        boolean wasActive = be.active;
        be.active = world.isClientSide ? state.getValue(ProjectorBlock.POWERED) : world.hasNeighborSignal(pos);
        if (!world.isClientSide && state.getValue(ProjectorBlock.POWERED) != be.active) {
            world.setBlock(pos, state.setValue(ProjectorBlock.POWERED, be.active), Block.UPDATE_CLIENTS);
        }
        if (wasActive != be.active) {
            be.logPowerChange(world, "neighbor-signal", wasActive);
            be.setChanged();
        }
        if (!wasActive && be.active) {
            be.revealDistance = -1;
            be.clientRevealDistance = -1;
        }
        be.tickBeaconRotation();
        be.tickProjection(world);
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putFloat("rotationBeacon", rotationBeacon);
        tag.putString("channel", channel);
        tag.putBoolean("active", active);
        tag.putInt("targetDistance", revealDistance);
        tag.putLong("projectionSurfaceVersion", projectionSurfaceVersion);

        super.saveAdditional(tag, registryLookup);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registryLookup) {
        boolean wasActive = active;
        String previousChannel = channel;
        channel = tag.getString("channel");
        if (!beaconAnimationInitialized) {
            rotationBeacon = Mth.positiveModulo(tag.getFloat("rotationBeacon"), 360.0F);
            rotationBeaconPrev = rotationBeacon;
        }
        active = tag.getBoolean("active");
        revealDistance = tag.contains("targetDistance") ? tag.getInt("targetDistance") : -1;
        projectionSurfaceVersion = Math.max(0L, tag.getLong("projectionSurfaceVersion"));
        if (!wasActive && active || !previousChannel.equals(channel)) {
            clientRevealDistance = -1;
            clientProjectionReady = false;
        }
        if (level != null && wasActive != active) {
            logPowerChange(level, "block-entity-sync", wasActive);
        }

        super.loadAdditional(tag, registryLookup);
    }

    public String getChannel() {
        return channel;
    }

    private void logPowerChange(Level world, String cause, boolean wasActive) {
        LOGGER.info("[GLASS projector] power side={} dimension={} pos={} channel={} cause={} active={}->{} reveal={} clientReady={} surfaceVersion={} tick={}",
                world.isClientSide ? "client" : "server", world.dimension().location(), worldPosition.toShortString(),
                channel, cause, wasActive, active, getRevealDistance(), clientProjectionReady, projectionSurfaceVersion, world.getGameTime());
    }

    public void setChannelFromServer(String channel) {
        String value = Objects.requireNonNull(channel);
        if (this.channel.equals(value)) {
            return;
        }
        this.channel = value;
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registryLookup) {
        return saveWithoutMetadata(registryLookup);
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("G.L.A.S.S Projector");
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
        ChannelManagerPersistence channelManager = ChannelManagerPersistence.get(player.level());

        return new ProjectorBlockGUI(syncId, inventory, new ScreenHandlerData(channel, this.getBlockPos(), channelManager.save(new CompoundTag(), null)));
    }

    public record ScreenHandlerData(String channel, BlockPos pos, CompoundTag compound) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenHandlerData> CODEC = StreamCodec.of(
                (buf, instance) -> {
                    buf.writeUtf(instance.channel);
                    buf.writeBlockPos(instance.pos);
                    buf.writeNbt(instance.compound);
                },
                (buf) -> new ScreenHandlerData(buf.readUtf(), buf.readBlockPos(), buf.readNbt())
        );
    }

    @Override
    public ScreenHandlerData getScreenOpeningData(ServerPlayer player) {
        ChannelManagerPersistence channelManager = ChannelManagerPersistence.get(player.level());
        return new ScreenHandlerData(channel, worldPosition, channelManager.save(new CompoundTag(), null));
    }

    private void tickBeaconRotation() {
        beaconAnimationInitialized = true;
        rotationBeaconPrev = Mth.positiveModulo(rotationBeacon, 360.0F);
        beaconSpeedPrev = beaconSpeed;
        beaconSpinProgress = Mth.clamp(beaconSpinProgress + (active ? 1.0F : -1.0F) / BEACON_RAMP_TICKS, 0.0F, 1.0F);
        float easedProgress = beaconSpinProgress * beaconSpinProgress * (3.0F - 2.0F * beaconSpinProgress);
        beaconSpeed = BEACON_MAX_SPEED * easedProgress;
        rotationBeacon = rotationBeaconPrev + (beaconSpeedPrev + beaconSpeed) * 0.5F;
    }

    public float getBeaconRotation(float partialTick) {
        float progress = Mth.clamp(partialTick, 0.0F, 1.0F);
        return rotationBeaconPrev + beaconSpeedPrev * progress
                + (beaconSpeed - beaconSpeedPrev) * progress * progress * 0.5F;
    }

    public float getBeaconSpeed(float partialTick) {
        return Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), beaconSpeedPrev, beaconSpeed);
    }

    private void tickProjection(Level world) {
        if (!active && getRevealDistance() < 0) {
            return;
        }
        Direction facing = getBlockState().getValue(ProjectorBlock.FACING);
        ensureProjectionSurface(world, facing);
        if (world.isClientSide) {
            if (active) {
                if (clientProjectionReady && clientRevealDistance < projectionSurface.completedRevealDistance()) {
                    clientRevealDistance++;
                    playPanelTransitionSounds(world, GSounds.PROJECTION_PANEL_ACTIVATE, clientRevealDistance);
                }
            } else if (clientRevealDistance >= 0) {
                playPanelTransitionSounds(world, GSounds.PROJECTION_PANEL_DEACTIVATE, clientRevealDistance);
                clientRevealDistance--;
            }
            return;
        }
        int oldRevealDistance = revealDistance;
        if (active) {
            revealDistance = Math.min(projectionSurface.completedRevealDistance(), revealDistance + 1);
        } else {
            revealDistance = Math.max(-1, revealDistance - 1);
        }
        if (revealDistance != oldRevealDistance) {
            setChanged();
        }
    }

    private void playPanelTransitionSounds(Level world, SoundEvent sound, int distance) {
        for (ProjectionSurface.Face face : projectionSurface.faces()) {
            if (face.revealDistance() != distance) {
                continue;
            }
            BlockPos position = face.position();
            Direction normal = face.normal();
            world.playLocalSound(
                    position.getX() + 0.5D + normal.getStepX() * 0.5D,
                    position.getY() + 0.5D + normal.getStepY() * 0.5D,
                    position.getZ() + 0.5D + normal.getStepZ() * 0.5D,
                    sound,
                    SoundSource.BLOCKS,
                    0.6F,
                    0.8F + world.random.nextFloat() * 0.4F,
                    false
            );
        }
    }

    private void ensureProjectionSurface(Level world, Direction facing) {
        boolean facingChanged = projectionSurface != null && projectionSurface.facing() != facing;
        ProjectionSurface.BlockQuery query = position -> sampleBlock(world, position);
        if (projectionSurface != null && projectionSurface.isTopologyValid(facing, query)) {
            return;
        }

        long nextVersion = Math.incrementExact(projectionSurfaceVersion);
        ProjectionSurface rebuilt = ProjectionSurface.rebuild(
                worldPosition,
                facing,
                nextVersion,
                query
        );
        if (projectionSurface != null && projectionSurface.hasSameTopology(rebuilt)) {
            projectionSurface = rebuilt.withVersion(projectionSurface.version());
            return;
        }

        projectionSurface = rebuilt;
        projectionSurfaceVersion = nextVersion;
        if (facingChanged) {
            revealDistance = -1;
            clientRevealDistance = -1;
        } else {
            revealDistance = Math.min(revealDistance, rebuilt.completedRevealDistance());
            clientRevealDistance = Math.min(clientRevealDistance, rebuilt.completedRevealDistance());
        }
        setChanged();
    }

    private static ProjectionSurface.BlockSample sampleBlock(Level world, BlockPos position) {
        int chunkX = SectionPos.blockToSectionCoord(position.getX());
        int chunkZ = SectionPos.blockToSectionCoord(position.getZ());
        if (!world.getChunkSource().hasChunk(chunkX, chunkZ)) {
            return ProjectionSurface.BlockSample.UNLOADED;
        }
        return world.getBlockState(position).is(Blocks.GLASS)
                ? ProjectionSurface.BlockSample.GLASS
                : ProjectionSurface.BlockSample.OTHER;
    }

    @Nullable
    public ProjectionSurface getProjectionSurface() {
        return projectionSurface;
    }

    public void prepareProjectionSurface() {
        if (level != null) {
            long tick = level.getGameTime();
            if (projectionSurface != null && projectionSurface.facing() == getBlockState().getValue(ProjectorBlock.FACING)
                    && lastPreparationTick != Long.MIN_VALUE && tick - lastPreparationTick < 10L) {
                return;
            }
            lastPreparationTick = tick;
            ensureProjectionSurface(level, getBlockState().getValue(ProjectorBlock.FACING));
        }
    }

    public void setClientProjectionReady(boolean ready) {
        clientProjectionReady = ready;
    }

    public int getRevealDistance() {
        return level != null && level.isClientSide ? clientRevealDistance : revealDistance;
    }

    public boolean isProjectionVisible() {
        return projectionSurface != null && (active || getRevealDistance() >= 0);
    }

    public boolean isActive() {
        return active;
    }
}
