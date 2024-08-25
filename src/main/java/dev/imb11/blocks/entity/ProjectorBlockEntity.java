package dev.imb11.blocks.entity;

import dev.imb11.Glass;
import dev.imb11.blocks.GBlocks;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.util.BoundingBox2D;
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
    public static int FADEOUT_TIME_MAX = 12;    public static BlockEntityType<ProjectorBlockEntity> BLOCK_ENTITY_TYPE = FabricBlockEntityTypeBuilder.create(ProjectorBlockEntity::new, GBlocks.PROJECTOR).build();
    public final ArrayList<Pair<BlockPos, Integer>> neighbouringGlassBlocks = new ArrayList<>();
    private final Set<BlockPos> visitedBlocks = new HashSet<>();
    public int fadeoutTime = 12;
    public boolean active = false;
    public long activeSince = -1;
    public String channel = "";
    public Direction facing = Direction.UP;
    // Rendering variables
    public int targetDistance = 0;
    public long deactiveSince = -1;
    public float rotationBeacon, rotationBeaconPrev;
    public int furthestBlock = 0;
    private BoundingBox2D boundingBox;
    private Portal portal = null;

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

    public static void tick(World world, BlockPos pos, BlockState state, ProjectorBlockEntity be) {
        be.tick(world);

        be.active = world.isReceivingRedstonePower(pos);
        float rotationFactor = be.active ? ((float) be.fadeoutTime / FADEOUT_TIME_MAX) : (1.0F - ((float) be.fadeoutTime / FADEOUT_TIME_MAX));
        if (rotationFactor > 0) {
            be.rotationBeacon += 20F * rotationFactor;
        }
        be.rotationBeaconPrev = be.rotationBeacon;


        if (be.active && be.activeSince != -1) {
            long activeSince = be.activeSince;
            be.deactiveSince = -1;
            if (be.activeSince == -1) {
                be.activeSince = activeSince;
            }

            int maxDistance = be.furthestBlock + 3;
            be.targetDistance = (int) Math.min(maxDistance, (System.currentTimeMillis() - activeSince) / 50);
            be.markDirty();
        } else {
            if (be.deactiveSince == -1) {
                be.deactiveSince = System.currentTimeMillis();
                be.markDirty();
            }

            // Decrement the target distance to -1 every 25ms
            if (be.targetDistance > -3 && System.currentTimeMillis() - be.deactiveSince > 25L) {
                be.targetDistance--;
                be.deactiveSince = System.currentTimeMillis();
                be.markDirty();
            }
        }
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

    public void createPortal(ServerWorld world, int targetDistance) {
        this.boundingBox = null;

        // Get where distance is 0, then make
        for (Pair<BlockPos, Integer> neighbouringGlassBlock : neighbouringGlassBlocks) {
            if (neighbouringGlassBlock.getRight() == 0) {
                boundingBox = new BoundingBox2D(neighbouringGlassBlock.getLeft(), this.facing);
                break;
            }
        }

        if (boundingBox == null) {
            Glass.LOGGER.error("Failed to create bounding box for rendering portal");
            return;
        }

        for (Pair<BlockPos, Integer> neighbouringGlassBlock : this.neighbouringGlassBlocks) {
            BlockPos pos = neighbouringGlassBlock.getLeft();
            int distance = neighbouringGlassBlock.getRight();

            if (distance == 0) {
                continue;
            }

            if (boundingBox != null) {
                boundingBox.addBlockPos(pos, distance);
            }
        }

        Glass.LOGGER.info("Bounding box: " + boundingBox);

        Portal portal = Portal.entityType.create(world);
        portal.setDestinationDimension(World.OVERWORLD);
        portal.setDestination(new Vec3d(20, -57, 6));
        portal.setInteractable(false);
        portal.setTeleportable(false);

        Vec3d axisW;
        Vec3d axisH;

        switch (facing) {
            case NORTH:
                axisW = new Vec3d(1, 0, 0); // Width along X-axis
                axisH = new Vec3d(0, 1, 0); // Height along Y-axis
                break;
            case SOUTH:
                axisW = new Vec3d(-1, 0, 0); // Width along -X-axis
                axisH = new Vec3d(0, 1, 0); // Height along Y-axis
                break;
            case EAST:
                axisW = new Vec3d(0, 0, -1); // Width along -Z-axis
                axisH = new Vec3d(0, 1, 0); // Height along Y-axis
                break;
            case WEST:
                axisW = new Vec3d(0, 0, 1); // Width along Z-axis
                axisH = new Vec3d(0, 1, 0); // Height along Y-axis
                break;
            case DOWN:
                axisW = new Vec3d(1, 0, 0); // Width along X-axis
                axisH = new Vec3d(0, 0, 1); // Height along Z-axis
                break;
            case UP:
                axisW = new Vec3d(1, 0, 0); // Width along X-axis
                axisH = new Vec3d(0, 0, -1); // Height along -Z-axis
                break;
            default:
                throw new IllegalArgumentException("Unsupported facing direction: " + facing);
        }

        // Set portal orientation and size
        portal.setOrientationAndSize(axisW, axisH, boundingBox.getWidth(), boundingBox.getHeight());

        switch (facing) {
            case NORTH, SOUTH:
                // Adjust facepos accordingly
                Vec3d facePos = boundingBox.getMidpoint();

                // TODO: FX look at this, how do I properly center the portal? This only works for one use-case of the portal.
                facePos = facePos.add(new Vec3d(0.5D, 0.5D, 0.5D).multiply(BoundingBox2D.getRelativeUpVector(facing)));
                facePos = facePos.add(new Vec3d(0.5D, 0, facing == Direction.SOUTH ? 1.0D : 0.0D));
                facePos = facePos.add(2, 0, 0);
                facePos = facePos.add(new Vec3d(0.005D, 0.005D, 0.005D).multiply(Vec3d.of(facing.getVector())));

                // Rotate 180 to face the opposite direction
                portal.setOrientationRotation(DQuaternion.rotationByDegrees(new Vec3d(0, 1, 0), facing == Direction.NORTH ? 180 : 0));
                portal.setOriginPos(facePos);
        }

        portal.specialShape = new GeometryPortalShape();
        boundingBox.addSquares(this.getPos(), portal, targetDistance);

        portal.getWorld().spawnEntity(portal);

        this.portal = portal;
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

    public void tick(World world) {
        if (active) {
            if (activeSince == -1) {
                activeSince = System.currentTimeMillis();
                neighbouringGlassBlocks.clear();
                visitedBlocks.clear();
                neighbouringGlassBlocks.add(new Pair<>(pos, 0));
                visitedBlocks.add(pos);
                checkNeighbors(facing, neighbouringGlassBlocks, 0, pos, world);

                if (!world.isClient) {
                    createPortal((ServerWorld) world, targetDistance);
                }
            }

            fadeoutTime = FADEOUT_TIME_MAX;
        } else {
            activeSince = -1;

            if (fadeoutTime > 0) {
                fadeoutTime--;
            } else {
                fadeoutTime = 0;

                if (portal != null && !world.isClient) {
                    portal.remove(Entity.RemovalReason.DISCARDED);
                    portal = null;
                }
            }
        }

        if (this.boundingBox != null && this.portal != null) {
            boundingBox.addSquares(this.getPos(), portal, targetDistance);
            portal.reloadAndSyncToClient();
        }
    }

    private void checkNeighbors(Direction plane, ArrayList<Pair<BlockPos, Integer>> map, int distanceFromRoot, BlockPos currentPos, World world) {
        Direction[] directionsToCheck = getDirections(plane);
        Queue<Pair<BlockPos, Integer>> queue = new LinkedList<>();
        Set<BlockPos> visitedBlocks = new HashSet<>(); // Ensure you have a Set to track visited blocks
        queue.add(new Pair<>(currentPos, distanceFromRoot));
        visitedBlocks.add(currentPos);

        while (!queue.isEmpty()) {
            Pair<BlockPos, Integer> current = queue.poll();
            BlockPos pos = current.getLeft();
            int currentDistance = current.getRight();

            for (Direction direction : directionsToCheck) {
                BlockPos neighborPos = pos.offset(direction);
                if (!visitedBlocks.contains(neighborPos) && world.getBlockState(neighborPos).getBlock() instanceof GlassBlock) {
                    visitedBlocks.add(neighborPos);
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


}