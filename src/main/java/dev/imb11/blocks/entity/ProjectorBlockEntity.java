package dev.imb11.blocks.entity;

import dev.imb11.blocks.GBlocks;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.GlassBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import qouteall.imm_ptl.core.portal.GeometryPortalShape;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.*;

public class ProjectorBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory {
    public static BlockEntityType<ProjectorBlockEntity> BLOCK_ENTITY_TYPE = FabricBlockEntityTypeBuilder.create(ProjectorBlockEntity::new, GBlocks.PROJECTOR).build();
    public static int FADEOUT_TIME_MAX = 12;

    public int fadeoutTime = 12;
    public boolean active = false;
    public long activeSince = -1;
    public String channel = "";
    public Direction facing = Direction.UP;

    // Rendering variables
    public int targetDistance = 0;
    public long deactiveSince = -1;

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
        tag.putLong("activeSince", activeSince);
        tag.putLong("deactiveSince", deactiveSince);
        tag.putInt("targetDistance", targetDistance);

        super.writeNbt(tag);
    }

    private Portal portal = null;
    public void createPortal(ServerWorld world) {
        var portalTriangles = new GeometryPortalShape();

        BlockPos rootPos = this.getPos();


        int halfWidth = actualWidth / 2;
        int halfHeight = actualHeight / 2;

        // A map of blockpos and distance from rootPos
        for (Pair<BlockPos, Integer> neighbouringGlassBlock : this.neighbouringGlassBlocks) {
            BlockPos relativePos = neighbouringGlassBlock.getLeft().subtract(rootPos);

            int x1 = relativePos.getX();
            int y1 = relativePos.getY();
            int x2 = x1 + 1;
            int y2 = y1 + 1;

            // Normalize to range [-1, 1]
            double normalizedX1 = (double) (x1 - halfWidth) / halfWidth;
            double normalizedY1 = (double) (y1 - halfHeight) / halfHeight;
            double normalizedX2 = (double) (x2 - halfWidth) / halfWidth;
            double normalizedY2 = (double) (y2 - halfHeight) / halfHeight;

            portalTriangles.addTriangleForRectangle(normalizedX1, normalizedY1, normalizedX2, normalizedY2);
        }

        Vec3d facePos = rootPos.toCenterPos();

        facePos.add(new Vec3d(0.51, 0.51, 0.51).multiply(Vec3d.of(facing.getVector())));

        this.portal = Portal.entityType.create(world);
        portal.setOriginPos(facePos);
        portal.setDestinationDimension(World.OVERWORLD);
        portal.setDestination(new Vec3d(0, 70, 0));
        portal.setInteractable(false);
        portal.setTeleportable(false);
        portal.setOrientationAndSize(
                new Vec3d(0, 1, 0), // axisW
                new Vec3d(0, 0, 1), // axisH
                furthestBlock,
                furthestBlock
        );

        portal.getWorld().spawnEntity(portal);
    }

    @Override
    public void readNbt(NbtCompound tag) {
        facing = Direction.byId(tag.getInt("facing"));
        channel = tag.getString("channel");
        rotationBeacon = tag.getFloat("rotationBeacon");
        rotationBeaconPrev = tag.getFloat("rotationBeaconPrev");
        active = tag.getBoolean("active");
        fadeoutTime = tag.getInt("fadeoutTime");
        activeSince = tag.getLong("activeSince");
        deactiveSince = tag.getLong("deactiveSince");
        targetDistance = tag.getInt("targetDistance");

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

    public int furthestBlock = 0;
    public final ArrayList<Pair<BlockPos, Integer>> neighbouringGlassBlocks = new ArrayList<>();
    private final Set<BlockPos> visitedBlocks = new HashSet<>();

    public void tick(World world) {
        if (active) {
            if (activeSince == -1) {
                activeSince = System.currentTimeMillis();
                neighbouringGlassBlocks.clear();
                visitedBlocks.clear();
                neighbouringGlassBlocks.add(new Pair<>(pos, 0));
                visitedBlocks.add(pos);
                checkNeighbors(facing, neighbouringGlassBlocks, 0, pos, world);

                if(!world.isClient) {
                    createPortal((ServerWorld) world);
                }
            }
            fadeoutTime = FADEOUT_TIME_MAX;
        } else {
            activeSince = -1;

            if (portal != null && !world.isClient) {
                portal.remove(Entity.RemovalReason.DISCARDED);
                portal = null;
            }

            if (fadeoutTime > 0) {
                fadeoutTime--;
            } else {
                fadeoutTime = 0;
            }
        }
    }

    private void checkNeighbors(Direction plane, ArrayList<Pair<BlockPos, Integer>> map, int distanceFromRoot, BlockPos currentPos, World world) {
        Direction[] directionsToCheck = getDirections(plane);
        Queue<Pair<BlockPos, Integer>> queue = new LinkedList<>();
        queue.add(new Pair<>(currentPos, distanceFromRoot));

        while (!queue.isEmpty()) {
            Pair<BlockPos, Integer> current = queue.poll();
            currentPos = current.getLeft();
            int currentDistance = current.getRight();

            for (Direction direction : directionsToCheck) {
                BlockPos neighborPos = currentPos.offset(direction);
                if (world.getBlockState(neighborPos).getBlock() instanceof GlassBlock && visitedBlocks.add(neighborPos)) {
                    Pair<BlockPos, Integer> neighborPair = new Pair<>(neighborPos, currentDistance + 1);
                    map.add(neighborPair);
                    queue.add(neighborPair);

                    // Update furthestBlock accordingly
                    if (currentDistance + 1 > furthestBlock) {
                        furthestBlock = currentDistance + 1;
                    }
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