package dev.imb11.sync.remote;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record S2CRemoteBlockEntitiesPacket(RemoteSubscriptionId subscription, long sequence, List<ClientboundBlockEntityDataPacket> updates) implements CustomPacketPayload {
    public static final int MAX_UPDATES = 32;
    public static final Type<S2CRemoteBlockEntitiesPacket> PACKET_ID = new Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remote_block_entities"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRemoteBlockEntitiesPacket> PACKET_CODEC = StreamCodec.ofMember(S2CRemoteBlockEntitiesPacket::write, S2CRemoteBlockEntitiesPacket::read);

    public S2CRemoteBlockEntitiesPacket {
        updates = List.copyOf(updates);
        if (sequence < 0L || updates.isEmpty() || updates.size() > MAX_UPDATES) {
            throw new IllegalArgumentException("invalid remote block entity batch");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        subscription.write(buf);
        buf.writeVarLong(sequence);
        buf.writeVarInt(updates.size());
        for (ClientboundBlockEntityDataPacket update : updates) {
            ClientboundBlockEntityDataPacket.STREAM_CODEC.encode(buf, update);
        }
    }

    private static S2CRemoteBlockEntitiesPacket read(RegistryFriendlyByteBuf buf) {
        RemoteSubscriptionId subscription = RemoteSubscriptionId.read(buf);
        long sequence = buf.readVarLong();
        int size = buf.readVarInt();
        if (size < 1 || size > MAX_UPDATES) {
            throw new IllegalArgumentException("invalid remote block entity batch size");
        }
        List<ClientboundBlockEntityDataPacket> updates = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            updates.add(ClientboundBlockEntityDataPacket.STREAM_CODEC.decode(buf));
        }
        return new S2CRemoteBlockEntitiesPacket(subscription, sequence, updates);
    }
}
