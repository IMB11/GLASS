package dev.imb11.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.function.Consumer;

public final class ClientNetworking {
    private ClientNetworking() {
    }

    public static <T extends CustomPacketPayload> void registerReceiver(CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        PlatformNetworking.registerClientHandler(type, handler);
    }

    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(type.id());
    }

    public static void send(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
