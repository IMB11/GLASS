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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

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

            // Decrement the target distance to -1 every 25ms
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

        if (this.portal != null) {
            tag.putUUID("portal", this.portal.getUUID());
        }

        super.saveAdditional(tag, registryLookup);
    }

    public void createPortal(ServerLevel world) {
        this.boundingBox = null;

        Direction facing = this.getBlockState().getValue(ProjectorBlock.FACING);

        // Get where distance is 0, then make
        for (Tuple<BlockPos, Integer> neighbouringGlassBlock : neighbouringGlassBlocks) {
            if (neighbouringGlassBlock.getB() == 0) {
                boundingBox = new BoundingBox2D(neighbouringGlassBlock.getA(), facing);
                break;
            }
        }

        if (boundingBox == null) {
            Glass.LOGGER.error("Failed to create bounding box for rendering portal");
            return;
        }

        for (Tuple<BlockPos, Integer> neighbouringGlassBlock : this.neighbouringGlassBlocks) {
            BlockPos pos = neighbouringGlassBlock.getA();
            int distance = neighbouringGlassBlock.getB();

            if (distance == 0) {
                continue;
            }

            if (boundingBox != null) {
                boundingBox.addBlockPos(pos, distance);
            }
        }

        Glass.LOGGER.info("Bounding box: {}", boundingBox);

        ChannelManagerPersistence channelManager = ChannelManagerPersistence.get(world);
        Channel channel = channelManager.CHANNELS.get(this.channel);

        if (channel == null || channel.linkedBlock() == null) {
            Glass.LOGGER.error("This projector is not linked to a valid channel!");
            return;
        }

        Portal portal = Portal.ENTITY_TYPE.create(world);

        portal.setDestinationDimension(Level.OVERWORLD); // TODO: Use GlobalPos

        Vec3 offset = switch (facing.getOpposite()) {
            case NORTH -> new Vec3(0, 0, -1);
            case SOUTH -> new Vec3(0, 0, 1);
            case EAST -> new Vec3(1, 0, 0);
            case WEST -> new Vec3(-1, 0, 0);
            case UP -> new Vec3(0, 1, 0);
            case DOWN -> new Vec3(0, -1, 0);
        };

        // Apply the offset to the destination position
        portal.setDestination(channel.linkedBlock().getCenter().add(offset));

        portal.setInteractable(false);
        portal.setTeleportable(false);

        // Set portal size
        portal.setWidth(boundingBox.getWidth());
        portal.setHeight(boundingBox.getHeight());

        // Set portal orientation
        switch (facing) {
            case NORTH, SOUTH:
                portal.setOrientationRotation(DQuaternion.rotationByDegrees(new Vec3(0, 1, 0), facing == Direction.NORTH ? 180 : 0));
                break;
            case EAST, WEST:
                portal.setOrientationRotation(DQuaternion.rotationByDegrees(new Vec3(0, 1, 0), facing == Direction.EAST ? 90 : -90));
                break;
            case UP, DOWN:
                portal.setOrientationRotation(DQuaternion.rotationByDegrees(new Vec3(1, 0, 0), facing == Direction.DOWN ? 90 : -90));
                break;
        }

        // Populate portal tiles
        boolean shouldFlip = facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE;
        boundingBox.addSquares(this.getBlockPos(), portal, 0, shouldFlip);

        // Set portal position
        Vec3 facePos = this.boundingBox.getMidpoint();
        facePos = facePos.add(new Vec3(0.001d, 0.001d, 0.001d).multiply(Vec3.atLowerCornerOf(facing.getNormal())));
        portal.setOriginPos(facePos);

        portal.level().addFreshEntity(portal);

        this.portal = portal;
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

        try {
            if (!this.level.isClientSide) {
                var serverWorld = (ServerLevel) this.level;
                this.portal = (Portal) serverWorld.getEntity(tag.getUUID("portal"));
            }
        } catch (Exception ignored) {}

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

                if (!world.isClientSide) {
                    createPortal((ServerLevel) world);
                }
            }

            fadeoutTime = FADEOUT_TIME_MAX;
        } else {
            activeSince = -1;

            if (fadeoutTime > 0) {
                fadeoutTime--;
            } else {
                fadeoutTime = 0;

                if (portal != null && !world.isClientSide) {
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
            boundingBox.addSquares(this.getBlockPos(), portal, Math.max(targetDistance, 0), shouldFlip);
            portal.reloadAndSyncToClient();
        }
    }

    private void checkNeighbors(Direction plane, ArrayList<Tuple<BlockPos, Integer>> map, BlockPos currentPos, Level world) {
        Direction[] directionsToCheck = getDirections(plane);
        Queue<Tuple<BlockPos, Integer>> queue = new LinkedList<>();
        Set<BlockPos> visitedBlocks = new HashSet<>(); // Ensure you have a Set to track visited blocks
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

                    // Update furthestBlock accordingly
                    if (currentDistance + 1 > furthestBlock) {
                        furthestBlock = currentDistance + 1;
                    }
                }
            }
        }
    }
}