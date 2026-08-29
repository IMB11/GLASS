package dev.imb11.blocks.entity;

import dev.imb11.blocks.GBlocks;
import dev.imb11.client.gui.TerminalBlockGUI;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class TerminalBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory {
    public static BlockEntityType<TerminalBlockEntity> BLOCK_ENTITY_TYPE = FabricBlockEntityTypeBuilder.create(TerminalBlockEntity::new, GBlocks.TERMINAL).build();

    public String channel = "";

    public TerminalBlockEntity(BlockPos pos, BlockState state) {
        super(BLOCK_ENTITY_TYPE, pos, state);
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putString("channel", channel);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        channel = tag.getString("channel");
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
        // Using the block name as the screen title
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
    public ScreenHandlerData getScreenOpeningData(ServerPlayer player) {
        ChannelManagerPersistence channelManager = ChannelManagerPersistence.get(player.level());
        return new ScreenHandlerData(this.getBlockPos(), channelManager.save(new CompoundTag(), null));
    }
}