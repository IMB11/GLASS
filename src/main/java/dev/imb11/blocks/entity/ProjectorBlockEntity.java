package dev.imb11.blocks.entity;

import dev.imb11.Glass;
import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.ProjectorBlock;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.sync.Channel;
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
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.*;

public class ProjectorBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory {
    public static int FADEOUT_TIME_MAX = 12;
    public static BlockEntityType<ProjectorBlockEntity> BLOCK_ENTITY_TYPE = FabricBlockEntityTypeBuilder.create(ProjectorBlockEntity::new, GBlocks.PROJECTOR).build();
    public final ArrayList<Pair<BlockPos, Integer>> neighbouringGlassBlocks = new ArrayList<>();
    private final Set<BlockPos> visitedBlocks = new HashSet<>();
    public int fadeoutTime = 12;
    public boolean active = false;
    public long activeSince = -1;
    public String channel = "";
    // Rendering variables
    public int targetDistance = 0;
    public long deactiveSince = -1;
    public float rotationBeacon, rotationBeaconPrev;
    public int furthestBlock = 0;
    public BoundingBox2D boundingBox;
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

            int maxDistance = be.furthestBlock + 3;
            be.targetDistance = (int) Math.min(maxDistance, (System.currentTimeMillis() - activeSince) / 50);
            be.markDirty();
        } else {
            if (be.deactiveSince == -1) {
                be.deactiveSince = System.currentTimeMillis();
                be.markDirty();
            }

            // Decrement the target distance to -1 every 25ms
            if (be.targetDistance > -1 && System.currentTimeMillis() - be.deactiveSince > 25L) {
                be.targetDistance--;
                be.deactiveSince = System.currentTimeMillis();
                be.markDirty();
            }
        }
    }

    @Override
    public void writeNbt(NbtCompound tag) {
        tag.putFloat("rotationBeacon", rotationBeacon);
        tag.putFloat("rotationBeaconPrev", rotationBeaconPrev);
        tag.putString("channel", channel);
        tag.putBoolean("active", active);
        tag.putInt("fadeoutTime", fadeoutTime);
        tag.putLong("activeSince", activeSince);
        tag.putLong("deactiveSince", deactiveSince);
        tag.putInt("targetDistance", targetDistance);

        if (this.portal != null) {
            tag.putUuid("portal", this.portal.getUuid());
        }

        super.writeNbt(tag);
    }

    public void createPortal(ServerWorld world) {
        this.boundingBox = null;

        Direction facing = this.getCachedState().get(ProjectorBlock.FACING);

        // Get where distance is 0, then make
        for (Pair<BlockPos, Integer> neighbouringGlassBlock : neighbouringGlassBlocks) {
            if (neighbouringGlassBlock.getRight() == 0) {
                boundingBox = new BoundingBox2D(neighbouringGlassBlock.getLeft(), facing);
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

        ChannelManagerPersistence channelManager = ChannelManagerPersistence.get(world);
        Channel channel = channelManager.CHANNELS.get(this.channel);

        if (channel == null || channel.linkedBlock() == null || channel.dimension() == null) {
            Glass.LOGGER.error("This projector is not linked to a valid channel!");
            return;
        }

        Portal portal = Portal.entityType.create(world);
        portal.setDestinationDimension(channel.dimension()); // TODO: Use GlobalPos

        Vec3d offset = switch (facing.getOpposite()) {
            case NORTH -> new Vec3d(0, 0, -1);
            case SOUTH -> new Vec3d(0, 0, 1);
            case EAST -> new Vec3d(1, 0, 0);
            case WEST -> new Vec3d(-1, 0, 0);
            case UP -> new Vec3d(0, 1, 0);
            case DOWN -> new Vec3d(0, -1, 0);
        };

        // Apply the offset to the destination position
        portal.setDestination(channel.linkedBlock().toCenterPos().add(offset));

        portal.setInteractable(false);
        portal.setTeleportable(false);

        // Set portal size
        portal.setWidth(boundingBox.getWidth());
        portal.setHeight(boundingBox.getHeight());

        // Set portal orientation
        switch (facing) {
            case NORTH, SOUTH:
                portal.setOrientationRotation(DQuaternion.rotationByDegrees(new Vec3d(0, 1, 0), facing == Direction.NORTH ? 180 : 0));
                break;
            case EAST, WEST:
                portal.setOrientationRotation(DQuaternion.rotationByDegrees(new Vec3d(0, 1, 0), facing == Direction.EAST ? 90 : -90));
                break;
            case UP, DOWN:
                portal.setOrientationRotation(DQuaternion.rotationByDegrees(new Vec3d(1, 0, 0), facing == Direction.DOWN ? 90 : -90));
                break;
        }

        // Populate portal tiles
        boolean shouldFlip = facing.getDirection() == Direction.AxisDirection.NEGATIVE;
        boundingBox.addSquares(this.getPos(), portal, 0, shouldFlip);

        // Set portal position
        Vec3d facePos = this.boundingBox.getMidpoint();
        facePos = facePos.add(new Vec3d(0.001d, 0.001d, 0.001d).multiply(Vec3d.of(facing.getVector())));
        portal.setOriginPos(facePos);

        portal.getWorld().spawnEntity(portal);

        this.portal = portal;
    }

    @Override
    public void readNbt(NbtCompound tag) {
        channel = tag.getString("channel");
        rotationBeacon = tag.getFloat("rotationBeacon");
        rotationBeaconPrev = tag.getFloat("rotationBeaconPrev");
        active = tag.getBoolean("active");
        fadeoutTime = tag.getInt("fadeoutTime");
        activeSince = tag.getLong("activeSince");
        deactiveSince = tag.getLong("deactiveSince");
        targetDistance = tag.getInt("targetDistance");

        try {
            if (!this.world.isClient) {
                var serverWorld = (ServerWorld) this.world;
                this.portal = (Portal) serverWorld.getEntity(tag.getUuid("portal"));
            }
        } catch (Exception ignored) {}

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
        buf.writeGlobalPos(GlobalPos.create(this.world.getRegistryKey(), this.getPos()));
        buf.writeNbt(channelManager.writeNbt(new NbtCompound()));

        return new ProjectorBlockGUI(syncId, inventory, buf);
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        ChannelManagerPersistence channelManager = ChannelManagerPersistence.get(player.getWorld());
        buf.writeString(channel);
        buf.writeGlobalPos(GlobalPos.create(this.world.getRegistryKey(), this.getPos()));
        buf.writeNbt(channelManager.writeNbt(new NbtCompound()));
    }

    public void tick(World world) {
        Direction facing = this.getCachedState().get(ProjectorBlock.FACING);
        if (active) {
            if (activeSince == -1) {
                activeSince = System.currentTimeMillis();
                neighbouringGlassBlocks.clear();
                visitedBlocks.clear();
                neighbouringGlassBlocks.add(new Pair<>(pos, 0));
                visitedBlocks.add(pos);
                checkNeighbors(facing, neighbouringGlassBlocks, 0, pos, world);

                if (!world.isClient) {
                    createPortal((ServerWorld) world);
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
            boolean shouldFlip = switch (facing) {
                case NORTH -> true;
                default -> false;
            };
            boundingBox.addSquares(this.getPos(), portal, Math.max(targetDistance, 0), shouldFlip);
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