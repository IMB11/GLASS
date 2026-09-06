package dev.imb11.sync.packets;

import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.GNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record C2SRemoveLinkedChannelPacket(BlockPos pos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SRemoveLinkedChannelPacket> PACKET_ID = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remove_linked_channel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRemoveLinkedChannelPacket> PACKET_CODEC = StreamCodec.ofMember((value, buf) -> buf.writeBlockPos(value.pos()), buf -> new C2SRemoveLinkedChannelPacket(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    public static void receive(C2SRemoveLinkedChannelPacket payload, ServerPlayer player) {
        if (!GNetworking.canUseTerminal(player, payload.pos())) {
            return;
        }
        var level = player.serverLevel();
        if (level.getBlockEntity(payload.pos()) instanceof TerminalBlockEntity terminal) {
            terminal.setChannelFromServer("");
            ChannelManagerPersistence.get(level).unlinkTerminal(level, payload.pos());
        }
    }
}
