package dev.imb11.sync.remote;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record S2CRemoteEntitiesPacket(RemoteSubscriptionId subscription, long sequence, List<EntityMessage> messages) implements CustomPacketPayload {
    public static final int MAX_MESSAGES = 128;
    public static final Type<S2CRemoteEntitiesPacket> PACKET_ID = new Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remote_entities"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRemoteEntitiesPacket> PACKET_CODEC = StreamCodec.ofMember(S2CRemoteEntitiesPacket::write, S2CRemoteEntitiesPacket::read);
    private static final List<Entry<?>> ENTRIES = List.of(
            new Entry<>(ClientboundAddEntityPacket.class, ClientboundAddEntityPacket.STREAM_CODEC),
            new Entry<>(ClientboundAddExperienceOrbPacket.class, ClientboundAddExperienceOrbPacket.STREAM_CODEC),
            new Entry<>(ClientboundSetEntityDataPacket.class, ClientboundSetEntityDataPacket.STREAM_CODEC),
            new Entry<>(ClientboundMoveEntityPacket.Pos.class, ClientboundMoveEntityPacket.Pos.STREAM_CODEC),
            new Entry<>(ClientboundMoveEntityPacket.Rot.class, ClientboundMoveEntityPacket.Rot.STREAM_CODEC),
            new Entry<>(ClientboundMoveEntityPacket.PosRot.class, ClientboundMoveEntityPacket.PosRot.STREAM_CODEC),
            new Entry<>(ClientboundTeleportEntityPacket.class, ClientboundTeleportEntityPacket.STREAM_CODEC),
            new Entry<>(ClientboundRotateHeadPacket.class, ClientboundRotateHeadPacket.STREAM_CODEC),
            new Entry<>(ClientboundSetEntityMotionPacket.class, ClientboundSetEntityMotionPacket.STREAM_CODEC),
            new Entry<>(ClientboundSetEquipmentPacket.class, ClientboundSetEquipmentPacket.STREAM_CODEC),
            new Entry<>(ClientboundUpdateAttributesPacket.class, ClientboundUpdateAttributesPacket.STREAM_CODEC),
            new Entry<>(ClientboundSetPassengersPacket.class, ClientboundSetPassengersPacket.STREAM_CODEC),
            new Entry<>(ClientboundSetEntityLinkPacket.class, ClientboundSetEntityLinkPacket.STREAM_CODEC),
            new Entry<>(ClientboundAnimatePacket.class, ClientboundAnimatePacket.STREAM_CODEC),
            new Entry<>(ClientboundHurtAnimationPacket.class, ClientboundHurtAnimationPacket.STREAM_CODEC),
            new Entry<>(ClientboundEntityEventPacket.class, ClientboundEntityEventPacket.STREAM_CODEC),
            new Entry<>(ClientboundUpdateMobEffectPacket.class, ClientboundUpdateMobEffectPacket.STREAM_CODEC),
            new Entry<>(ClientboundRemoveMobEffectPacket.class, ClientboundRemoveMobEffectPacket.STREAM_CODEC),
            new Entry<>(ClientboundRemoveEntitiesPacket.class, ClientboundRemoveEntitiesPacket.STREAM_CODEC)
    );

    public S2CRemoteEntitiesPacket {
        messages = List.copyOf(messages);
        if (sequence < 0L || messages.isEmpty() || messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("invalid remote entity batch");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    public static boolean supports(Packet<?> packet) {
        return ENTRIES.stream().anyMatch(entry -> entry.packetClass().isInstance(packet));
    }

    private void write(RegistryFriendlyByteBuf buf) {
        subscription.write(buf);
        buf.writeVarLong(sequence);
        buf.writeVarInt(messages.size());
        for (EntityMessage message : messages) {
            buf.writeVarInt(message.entityId());
            int index = 0;
            while (index < ENTRIES.size() && !ENTRIES.get(index).packetClass().isInstance(message.packet())) {
                index++;
            }
            if (index == ENTRIES.size()) {
                throw new IllegalArgumentException("unsupported remote entity packet");
            }
            buf.writeVarInt(index);
            ENTRIES.get(index).write(buf, message.packet());
        }
    }

    private static S2CRemoteEntitiesPacket read(RegistryFriendlyByteBuf buf) {
        RemoteSubscriptionId subscription = RemoteSubscriptionId.read(buf);
        long sequence = buf.readVarLong();
        int count = buf.readVarInt();
        if (count < 1 || count > MAX_MESSAGES) {
            throw new IllegalArgumentException("invalid remote entity batch size");
        }
        java.util.ArrayList<EntityMessage> messages = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int entityId = buf.readVarInt();
            int type = buf.readVarInt();
            if (type < 0 || type >= ENTRIES.size()) {
                throw new IllegalArgumentException("invalid remote entity packet type");
            }
            messages.add(new EntityMessage(entityId, ENTRIES.get(type).codec().decode(buf)));
        }
        return new S2CRemoteEntitiesPacket(subscription, sequence, messages);
    }

    public record EntityMessage(int entityId, Packet<?> packet) {
    }

    private record Entry<T extends Packet<ClientGamePacketListener>>(Class<T> packetClass, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        private void write(RegistryFriendlyByteBuf buf, Packet<?> packet) {
            codec.encode(buf, packetClass.cast(packet));
        }
    }
}

