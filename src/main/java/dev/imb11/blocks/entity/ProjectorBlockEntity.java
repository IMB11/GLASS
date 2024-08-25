package dev.imb11.blocks.entity;

import dev.imb11.Glass;
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

        // Initialize with extreme values for bounding box calculation
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

// Traverse the list of positions to determine the bounding box
        for (Pair<BlockPos, Integer> pair : this.neighbouringGlassBlocks) {
            BlockPos pos = pair.getLeft();

            // Update min and max x and z
            if (pos.getX() < minX) minX = pos.getX();
            if (pos.getZ() < minZ) minZ = pos.getZ();
            if (pos.getX() > maxX) maxX = pos.getX();
            if (pos.getZ() > maxZ) maxZ = pos.getZ();
        }

// Step 1: Calculate the center of the bounding box
        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;

// Step 2: Determine the maximum half-dimension (to normalize to a square)
        int halfWidth = Math.max((maxX - minX) / 2, 1);
        int halfDepth = Math.max((maxZ - minZ) / 2, 1);
        int maxHalfDimension = Math.max(halfWidth, halfDepth);

// Step 3: Normalize each position
        List<Pair<Double, Double>> normalizedPositions = new ArrayList<>();

        for (Pair<BlockPos, Integer> pair : this.neighbouringGlassBlocks) {
            BlockPos pos = pair.getLeft();

            // Translate the position to the center of the bounding box
            int translatedX = pos.getX() - centerX;
            int translatedZ = pos.getZ() - centerZ;

            // Scale to fit within the normalized square (-1 to 1)
            double normalizedX = (double) translatedX / maxHalfDimension;
            double normalizedZ = (double) translatedZ / maxHalfDimension;

            // Add to the list of normalized positions
            normalizedPositions.add(new Pair<>(normalizedX, normalizedZ));

            // Print out the details
            System.out.println("(glass) Original Pos: " + pos + " Translated Pos: (" + translatedX + ", " + translatedZ + ") Normalized Pos: (" + normalizedX + ", " + normalizedZ + ")");
        }


        Vec3d facePos = new Vec3d(rootPos.getX(), rootPos.getY(), rootPos.getZ());

        this.portal = Portal.entityType.create(world);
        portal.setOriginPos(facePos);
        portal.setDestinationDimension(World.OVERWORLD);
        portal.setDestination(new Vec3d(0, 70, 0));
        portal.setInteractable(false);
        portal.setTeleportable(false);
        portal.setOrientationAndSize(
                new Vec3d(0, 1, 0), // axisW
                new Vec3d(0, 0, 1), // axisH
                halfWidth * 2,
                halfDepth * 2
        );

        portal.specialShape = portalTriangles;

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