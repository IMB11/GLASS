package dev.imb11.blocks.entity;

import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.ProjectorBlock;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.projection.ProjectionSurface;
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

import java.util.Objects;

public class ProjectorBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory {
    public static final int FADEOUT_TIME_MAX = 12;
    public static BlockEntityType<ProjectorBlockEntity> BLOCK_ENTITY_TYPE = FabricBlockEntityTypeBuilder.create(ProjectorBlockEntity::new, GBlocks.PROJECTOR).build();
    public int fadeoutTime = 12;
    public boolean active = false;
    private String channel = "";
    public float rotationBeacon, rotationBeaconPrev;

    private ProjectionSurface projectionSurface;
    private long projectionSurfaceVersion;
    private int revealDistance = -1;

    public ProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(BLOCK_ENTITY_TYPE, pos, state);
    }

    public static void tick(Level world, BlockPos pos, BlockState state, ProjectorBlockEntity be) {
        be.tickFadeout();
        boolean wasActive = be.active;
        be.active = world.hasNeighborSignal(pos);
        if (!wasActive && be.active) {
            be.revealDistance = -1;
        }
        float rotationFactor = be.active ? ((float) be.fadeoutTime / FADEOUT_TIME_MAX) : (1.0F - ((float) be.fadeoutTime / FADEOUT_TIME_MAX));
        if (rotationFactor > 0) {
            be.rotationBeacon += 20F * rotationFactor;
        }
        be.rotationBeaconPrev = be.rotationBeacon;
        be.tickProjection(world);
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putFloat("rotationBeacon", rotationBeacon);
        tag.putFloat("rotationBeaconPrev", rotationBeaconPrev);
        tag.putString("channel", channel);
        tag.putBoolean("active", active);
        tag.putInt("fadeoutTime", fadeoutTime);
        tag.putInt("targetDistance", revealDistance);
        tag.putLong("projectionSurfaceVersion", projectionSurfaceVersion);

        super.saveAdditional(tag, registryLookup);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registryLookup) {
        channel = tag.getString("channel");
        rotationBeacon = tag.getFloat("rotationBeacon");
        rotationBeaconPrev = tag.getFloat("rotationBeaconPrev");
        active = tag.getBoolean("active");
        fadeoutTime = tag.getInt("fadeoutTime");
        revealDistance = tag.contains("targetDistance") ? tag.getInt("targetDistance") : -1;
        projectionSurfaceVersion = Math.max(0L, tag.getLong("projectionSurfaceVersion"));

        super.loadAdditional(tag, registryLookup);
    }

    public String getChannel() {
        return channel;
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

    private void tickFadeout() {
        if (active) {
            fadeoutTime = FADEOUT_TIME_MAX;
        } else {
            if (fadeoutTime > 0) {
                fadeoutTime--;
            } else {
                fadeoutTime = 0;
            }
        }
    }

    private void tickProjection(Level world) {
        if (!active && revealDistance < 0) {
            return;
        }
        Direction facing = getBlockState().getValue(ProjectorBlock.FACING);
        ensureProjectionSurface(world, facing);
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
        } else {
            revealDistance = Math.min(revealDistance, rebuilt.completedRevealDistance());
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

    public int getRevealDistance() {
        return revealDistance;
    }

    public boolean isProjectionVisible() {
        return projectionSurface != null && (active || revealDistance >= 0);
    }

    public boolean isActive() {
        return active;
    }
}
