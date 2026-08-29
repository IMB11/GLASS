package dev.imb11.blocks.entity;

import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.ProjectorBlock;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class ProjectorBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory {
    public static int FADEOUT_TIME_MAX = 12;
    public static BlockEntityType<ProjectorBlockEntity> BLOCK_ENTITY_TYPE = FabricBlockEntityTypeBuilder.create(ProjectorBlockEntity::new, GBlocks.PROJECTOR).build();
    public final ArrayList<Tuple<BlockPos, Integer>> neighbouringGlassBlocks = new ArrayList<>();
    private final Set<BlockPos> visitedBlocks = new HashSet<>();
    public int fadeoutTime = 12;
    public boolean active = false;
    public long activeSince = -1;
    public String channel = "";

    public int targetDistance = 0;
    public long deactiveSince = -1;
    public float rotationBeacon, rotationBeaconPrev;
    public int furthestBlock = 0;

    public ProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(BLOCK_ENTITY_TYPE, pos, state);
    }

    private static Direction @NotNull [] getDirections(Direction plane) {
        Direction[] directionsToCheck;
        if (plane == Direction.UP || plane == Direction.DOWN) {
            directionsToCheck = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        } else if (plane == Direction.NORTH || plane == Direction.SOUTH) {
            directionsToCheck = new Direction[]{Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST};
        } else {
            directionsToCheck = new Direction[]{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH};
        }
        return directionsToCheck;
    }

    public static void tick(Level world, BlockPos pos, BlockState state, ProjectorBlockEntity be) {
        be.tick(world);

        be.active = world.hasNeighborSignal(pos);
        float rotationFactor = be.active ? ((float) be.fadeoutTime / FADEOUT_TIME_MAX) : (1.0F - ((float) be.fadeoutTime / FADEOUT_TIME_MAX));
        if (rotationFactor > 0) {
            be.rotationBeacon += 20F * rotationFactor;
        }
        be.rotationBeaconPrev = be.rotationBeacon;

        if (be.active && be.activeSince != -1) {
            long activeSince = be.activeSince;
            be.deactiveSince = -1;

            int maxDistance = be.furthestBlock + 3;
            be.targetDistance = (int) Math.min(maxDistance, (System.currentTimeMillis() - activeSince) / 50);
            be.setChanged();
        } else {
            if (be.deactiveSince == -1) {
                be.deactiveSince = System.currentTimeMillis();
                be.setChanged();
            }

            if (be.targetDistance > -1 && System.currentTimeMillis() - be.deactiveSince > 25L) {
                be.targetDistance--;
                be.deactiveSince = System.currentTimeMillis();
                be.setChanged();
            }
        }
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putFloat("rotationBeacon", rotationBeacon);
        tag.putFloat("rotationBeaconPrev", rotationBeaconPrev);
        tag.putString("channel", channel);
        tag.putBoolean("active", active);
        tag.putInt("fadeoutTime", fadeoutTime);
        tag.putLong("activeSince", activeSince);
        tag.putLong("deactiveSince", deactiveSince);
        tag.putInt("targetDistance", targetDistance);

        super.saveAdditional(tag, registryLookup);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registryLookup) {
        channel = tag.getString("channel");
        rotationBeacon = tag.getFloat("rotationBeacon");
        rotationBeaconPrev = tag.getFloat("rotationBeaconPrev");
        active = tag.getBoolean("active");
        fadeoutTime = tag.getInt("fadeoutTime");
        activeSince = tag.getLong("activeSince");
        deactiveSince = tag.getLong("deactiveSince");
        targetDistance = tag.getInt("targetDistance");

        super.loadAdditional(tag, registryLookup);
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

    public void tick(Level world) {
        Direction facing = this.getBlockState().getValue(ProjectorBlock.FACING);
        if (active) {
            if (activeSince == -1) {
                activeSince = System.currentTimeMillis();
                neighbouringGlassBlocks.clear();
                visitedBlocks.clear();
                neighbouringGlassBlocks.add(new Tuple<>(worldPosition, 0));
                visitedBlocks.add(worldPosition);
                checkNeighbors(facing, neighbouringGlassBlocks, worldPosition, world);

            }

            fadeoutTime = FADEOUT_TIME_MAX;
        } else {
            activeSince = -1;

            if (fadeoutTime > 0) {
                fadeoutTime--;
            } else {
                fadeoutTime = 0;
            }
        }
    }

    private void checkNeighbors(Direction plane, ArrayList<Tuple<BlockPos, Integer>> map, BlockPos currentPos, Level world) {
        Direction[] directionsToCheck = getDirections(plane);
        Queue<Tuple<BlockPos, Integer>> queue = new LinkedList<>();
        Set<BlockPos> visitedBlocks = new HashSet<>();
        queue.add(new Tuple<>(currentPos, 0));
        visitedBlocks.add(currentPos);

        while (!queue.isEmpty()) {
            Tuple<BlockPos, Integer> current = queue.poll();
            BlockPos pos = current.getA();
            int currentDistance = current.getB();

            for (Direction direction : directionsToCheck) {
                BlockPos neighborPos = pos.relative(direction);
                if (!visitedBlocks.contains(neighborPos) && world.getBlockState(neighborPos).getBlock().equals(Blocks.GLASS)) {
                    visitedBlocks.add(neighborPos);
                    Tuple<BlockPos, Integer> neighborPair = new Tuple<>(neighborPos, currentDistance + 1);
                    map.add(neighborPair);
                    queue.add(neighborPair);

                    if (currentDistance + 1 > furthestBlock) {
                        furthestBlock = currentDistance + 1;
                    }
                }
            }
        }
    }
}
