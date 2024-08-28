package dev.imb11.blocks.entity;

import dev.imb11.blocks.GBlocks;
import dev.imb11.client.gui.TerminalBlockGUI;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import org.jetbrains.annotations.Nullable;

public class TerminalBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory {
    public static BlockEntityType<TerminalBlockEntity> BLOCK_ENTITY_TYPE = FabricBlockEntityTypeBuilder.create(TerminalBlockEntity::new, GBlocks.TERMINAL).build();

    public String channel = "";

    public TerminalBlockEntity(BlockPos pos, BlockState state) {
        super(BLOCK_ENTITY_TYPE, pos, state);
    }

    @Override
    public void writeNbt(NbtCompound tag) {
        tag.putString("channel", channel);
    }

    @Override
    public void readNbt(NbtCompound tag) {
        channel = tag.getString("channel");
    }

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }


    @Override
    public BlockEntityType<?> getType() {
        return BLOCK_ENTITY_TYPE;
    }

    @Override
    public Text getDisplayName() {
        // Using the block name as the screen title
        return Text.literal("G.L.A.S.S Terminal");
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory inventory, PlayerEntity player) {

        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeGlobalPos(GlobalPos.create(this.world.getRegistryKey(), this.getPos()));

        ChannelManagerPersistence channelManager = ChannelManagerPersistence.get(player.getWorld());

        buf.writeNbt(channelManager.writeNbt(new NbtCompound()));

        return new TerminalBlockGUI(syncId, inventory, buf);
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        buf.writeGlobalPos(GlobalPos.create(this.world.getRegistryKey(), this.getPos()));
        ChannelManagerPersistence channelManager = ChannelManagerPersistence.get(player.getWorld());
        buf.writeNbt(channelManager.writeNbt(new NbtCompound()));

        System.out.println(channelManager.writeNbt(new NbtCompound()));
    }
}