package dev.imb11.blocks.entity;

import dev.imb11.blocks.GBlocks;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.GlassBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

public class ProjectorBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory {
    public static BlockEntityType<ProjectorBlockEntity> BLOCK_ENTITY_TYPE = FabricBlockEntityTypeBuilder.create(ProjectorBlockEntity::new, GBlocks.PROJECTOR).build();
    public static int FADEOUT_TIME_MAX = 12;

    public int fadeoutTime = 12;
    public boolean active = false;
    public long activeSince = -1;
    public String channel = "";
    public Direction facing = Direction.UP;

    public float rotationBeacon, rotationBeaconPrev;

    public ProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(BLOCK_ENTITY_TYPE, pos, state);
    }

    @Override
    public void writeNbt(NbtCompound tag) {
        tag.putInt("facing", facing.getId());
        tag.putFloat("rotationBeacon", rotationBeacon);
        tag.putFloat("rotationBeaconPrev", rotationBeaconPrev);
        tag.putString("channel", channel);
        tag.putBoolean("active", active);
        tag.putInt("fadeoutTime", fadeoutTime);
        super.writeNbt(tag);
    }

    @Override
    public void readNbt(NbtCompound tag) {
        facing = Direction.byId(tag.getInt("facing"));
        channel = tag.getString("channel");
        rotationBeacon = tag.getFloat("rotationBeacon");
        rotationBeaconPrev = tag.getFloat("rotationBeaconPrev");
        active = tag.getBoolean("active");
        fadeoutTime = tag.getInt("fadeoutTime");

        super.readNbt(tag);
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("G.L.A.S.S Projector");
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory inventory, PlayerEntity player) {

        PacketByteBuf buf = PacketByteBufs.create();

        ChannelManagerPersistence channelManager = ChannelManagerPersistence.get(player.getWorld());

        buf.writeString(channel);
        buf.writeBlockPos(pos);
        buf.writeNbt(channelManager.writeNbt(new NbtCompound()));

        return new ProjectorBlockGUI(syncId, inventory, buf);
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        ChannelManagerPersistence channelManager = ChannelManagerPersistence.get(player.getWorld());
        buf.writeString(channel);
        buf.writeBlockPos(pos);
        buf.writeNbt(channelManager.writeNbt(new NbtCompound()));
    }

    public final ArrayList<Pair<BlockPos, Integer>> neighbouringGlassBlocks = new ArrayList<>();

    public void tick(World world) {
        if (active) {
            if (activeSince == -1) {
                activeSince = System.currentTimeMillis();
                neighbouringGlassBlocks.clear();
                checkNeighbors(facing, neighbouringGlassBlocks, 0, pos, world);
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

    private static void checkNeighbors(Direction plane, ArrayList<Pair<BlockPos, Integer>> map, int distanceFromRoot, BlockPos currentPos, World world) {
        if (distanceFromRoot > 10) { // Limit the recursion depth to prevent stack overflow
            return;
        }

        // Define the directions to check based on the plane
        Direction[] directionsToCheck = getDirections(plane);

        for (Direction direction : directionsToCheck) {
            BlockPos neighborPos = currentPos.offset(direction);
            if (world.getBlockState(neighborPos).getBlock() instanceof GlassBlock) {
                Pair<BlockPos, Integer> neighborPair = new Pair<>(neighborPos, distanceFromRoot + 1);
                if (!map.contains(neighborPair)) {
                    map.add(neighborPair);
                    checkNeighbors(plane, map, distanceFromRoot + 1, neighborPos, world);
                }
            }
        }
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

    public static void tick(World world, BlockPos pos, BlockState state, ProjectorBlockEntity be) {
        be.tick(world);
        be.active = world.isReceivingRedstonePower(pos);

        float rotationFactor = be.active ? ((float) be.fadeoutTime / FADEOUT_TIME_MAX) : (1.0F - ((float) be.fadeoutTime / FADEOUT_TIME_MAX));

        if (rotationFactor > 0) {
            be.rotationBeacon += 20F * rotationFactor;
        }
        be.rotationBeaconPrev = be.rotationBeacon;
    }
}