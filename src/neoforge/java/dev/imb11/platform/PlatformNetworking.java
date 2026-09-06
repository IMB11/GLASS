package dev.imb11.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class PlatformNetworking {
    private static final List<Consumer<PayloadRegistrar>> REGISTRATIONS = new ArrayList<>();
    private static final Map<ResourceLocation, Consumer<CustomPacketPayload>> CLIENT_HANDLERS = new HashMap<>();

    private PlatformNetworking() {
    }

    public static <T extends CustomPacketPayload> void registerServerbound(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, BiConsumer<T, ServerPlayer> handler) {
        REGISTRATIONS.add(registrar -> registrar.playToServer(type, codec, (packet, context) ->
                handler.accept(packet, (ServerPlayer) context.player())));
    }

    public static <T extends CustomPacketPayload> void registerClientbound(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        REGISTRATIONS.add(registrar -> registrar.playToClient(type, codec, (packet, context) -> {
            Consumer<CustomPacketPayload> handler = CLIENT_HANDLERS.get(type.id());
            if (handler != null) {
                handler.accept(packet);
            }
        }));
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        REGISTRATIONS.forEach(registration -> registration.accept(registrar));
    }

    @SuppressWarnings("unchecked")
    public static <T extends CustomPacketPayload> void registerClientHandler(CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        CLIENT_HANDLERS.put(type.id(), packet -> handler.accept((T) packet));
    }

    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection.hasChannel(type.id());
    }

    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static Collection<ServerPlayer> tracking(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().chunkMap.getPlayers(new ChunkPos(pos), false);
    }

    public static void sendPairingData(ServerEntity entity, ServerPlayer player, Consumer<Packet<?>> consumer) {
        entity.sendPairingData(player, new PacketAndPayloadAcceptor<>(consumer::accept));
    }
}
