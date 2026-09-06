package dev.imb11.platform;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.function.Consumer;

public final class ClientNetworking {
    private ClientNetworking() {
    }

    public static <T extends CustomPacketPayload> void registerReceiver(CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        ClientPlayNetworking.registerGlobalReceiver(type, (packet, context) -> handler.accept(packet));
    }

    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        return ClientPlayNetworking.canSend(type);
    }

    public static void send(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }
}
