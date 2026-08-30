package dev.imb11.sync.packets;

import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.ProjectionSource;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

public record S2CChannelSnapshotPacket(long registryRevision, List<Channel> channels) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CChannelSnapshotPacket> PACKET_ID = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("glass", "channel_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CChannelSnapshotPacket> PACKET_CODEC = StreamCodec.ofMember(S2CChannelSnapshotPacket::write, S2CChannelSnapshotPacket::read);

    public S2CChannelSnapshotPacket {
        if (registryRevision < 0L) {
            throw new IllegalArgumentException("registry revision must be non-negative");
        }
        channels = List.copyOf(channels);
        if (channels.size() > ChannelManagerPersistence.MAX_CHANNELS) {
            throw new IllegalArgumentException("too many channels");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarLong(registryRevision);
        buf.writeVarInt(channels.size());
        for (Channel channel : channels) {
            buf.writeUtf(channel.name(), ChannelManagerPersistence.MAX_WIRE_CHANNEL_NAME_LENGTH);
            ProjectionSource source = channel.source();
            buf.writeBoolean(source != null);
            if (source != null) {
                buf.writeResourceLocation(source.dimension().location());
                buf.writeBlockPos(source.pos());
                buf.writeEnum(source.facing());
                buf.writeVarLong(source.revision());
            }
        }
    }

    private static S2CChannelSnapshotPacket read(RegistryFriendlyByteBuf buf) {
        long registryRevision = buf.readVarLong();
        int size = buf.readVarInt();
        if (size < 0 || size > ChannelManagerPersistence.MAX_CHANNELS) {
            throw new IllegalArgumentException("invalid channel count");
        }
        List<Channel> channels = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String name = buf.readUtf(ChannelManagerPersistence.MAX_WIRE_CHANNEL_NAME_LENGTH);
            ProjectionSource source = null;
            if (buf.readBoolean()) {
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, buf.readResourceLocation());
                source = new ProjectionSource(name, dimension, buf.readBlockPos(), buf.readEnum(Direction.class), buf.readVarLong());
            }
            channels.add(new Channel(name, source));
        }
        return new S2CChannelSnapshotPacket(registryRevision, channels);
    }
}
