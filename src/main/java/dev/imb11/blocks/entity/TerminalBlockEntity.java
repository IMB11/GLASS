package dev.imb11.blocks.entity;

import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.TerminalBlock;
import dev.imb11.client.gui.TerminalBlockGUI;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.platform.PlatformBlockEntity;
import dev.imb11.platform.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class TerminalBlockEntity extends PlatformBlockEntity implements ExtendedMenuProvider<TerminalBlockEntity.ScreenHandlerData> {
    public static BlockEntityType<TerminalBlockEntity> BLOCK_ENTITY_TYPE = BlockEntityType.Builder.of(TerminalBlockEntity::new, GBlocks.TERMINAL).build(null);

    private String channel = "";
    private Direction reconciledFacing;

    public TerminalBlockEntity(BlockPos pos, BlockState state) {
        super(BLOCK_ENTITY_TYPE, pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, TerminalBlockEntity terminal) {
        if (!(level instanceof ServerLevel serverLevel) || !state.hasProperty(TerminalBlock.FACING)) {
            return;
        }
        Direction facing = state.getValue(TerminalBlock.FACING);
        ChannelManagerPersistence channels = ChannelManagerPersistence.get(serverLevel);
        if (terminal.reconciledFacing != facing) {
            terminal.reconciledFacing = facing;
            channels.reconcileTerminal(terminal);
        }
        boolean projecting = channels.isProjecting(serverLevel, pos, terminal.channel);
        if (state.getValue(TerminalBlock.PROJECTING) != projecting) {
            level.setBlock(pos, state.setValue(TerminalBlock.PROJECTING, projecting), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.saveAdditional(tag, lookup);
        tag.putString("channel", channel);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.loadAdditional(tag, lookup);
        channel = tag.getString("channel");
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
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
        return saveWithoutMetadata(lookup);
    }

    @Override
    public BlockEntityType<?> getType() {
        return BLOCK_ENTITY_TYPE;
    }

    @Override
    public Component getDisplayName() {

        return Component.literal("G.L.A.S.S Terminal");
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
        ChannelManagerPersistence channelManager = ChannelManagerPersistence.get(player.level());
        return new TerminalBlockGUI(syncId, inventory, new ScreenHandlerData(this.getBlockPos(), channelManager.save(new CompoundTag(), null)));
    }

    public record ScreenHandlerData(BlockPos pos, CompoundTag channelManagerNbt) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenHandlerData> CODEC = StreamCodec.of(
                (buf, instance) -> {
                    buf.writeBlockPos(instance.pos());
                    buf.writeNbt(instance.channelManagerNbt());
                },
                (buf) -> {
                    BlockPos pos = buf.readBlockPos();
                    CompoundTag channelManagerNbt = buf.readNbt();
                    return new ScreenHandlerData(pos, channelManagerNbt);
                }
        );
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, ScreenHandlerData> getScreenOpeningCodec() {
        return ScreenHandlerData.CODEC;
    }

    @Override
    public ScreenHandlerData getScreenOpeningData(ServerPlayer player) {
        ChannelManagerPersistence channelManager = ChannelManagerPersistence.get(player.level());
        return new ScreenHandlerData(this.getBlockPos(), channelManager.save(new CompoundTag(), null));
    }
}
