package dev.imb11.platform;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class PlatformNetworking {
    private PlatformNetworking() {
    }

    public static <T extends CustomPacketPayload> void registerServerbound(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, BiConsumer<T, ServerPlayer> handler) {
        PayloadTypeRegistry.playC2S().register(type, codec);
        ServerPlayNetworking.registerGlobalReceiver(type, (packet, context) -> handler.accept(packet, context.player()));
    }

    public static <T extends CustomPacketPayload> void registerClientbound(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.playS2C().register(type, codec);
    }

    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return ServerPlayNetworking.canSend(player, type);
    }

    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    public static Collection<ServerPlayer> tracking(ServerLevel level, BlockPos pos) {
        return PlayerLookup.tracking(level, pos);
    }

    public static void sendPairingData(ServerEntity entity, ServerPlayer player, Consumer<Packet<?>> consumer) {
        entity.sendPairingData(player, consumer::accept);
    }
}
