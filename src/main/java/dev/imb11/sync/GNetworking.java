package dev.imb11.sync;

import dev.imb11.sync.packets.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.util.Identifier;

public class GNetworking {
    private static Identifier id(String id) {
        return Identifier.of("glass", id);
    }

    public static void initialize() {
        PayloadTypeRegistry.playC2S().register(C2SCreateChannelPacket.PACKET_ID, C2SCreateChannelPacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SDeleteChannelPacket.PACKET_ID, C2SDeleteChannelPacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(C2STerminalChannelChangedPacket.PACKET_ID, C2STerminalChannelChangedPacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SRemoveLinkedChannelPacket.PACKET_ID, C2SRemoveLinkedChannelPacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SProjectorChannelChangedPacket.PACKET_ID, C2SProjectorChannelChangedPacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SPopulateDefaultChannelPacket.PACKET_ID, C2SPopulateDefaultChannelPacket.PACKET_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(C2SCreateChannelPacket.PACKET_ID, new C2SCreateChannelPacket(null));
        ServerPlayNetworking.registerGlobalReceiver(C2SDeleteChannelPacket.PACKET_ID, new C2SDeleteChannelPacket(null));
        ServerPlayNetworking.registerGlobalReceiver(C2STerminalChannelChangedPacket.PACKET_ID, new C2STerminalChannelChangedPacket(null, null));
        ServerPlayNetworking.registerGlobalReceiver(C2SRemoveLinkedChannelPacket.PACKET_ID, new C2SRemoveLinkedChannelPacket(null));
        ServerPlayNetworking.registerGlobalReceiver(C2SProjectorChannelChangedPacket.PACKET_ID, new C2SProjectorChannelChangedPacket(null, null));
        ServerPlayNetworking.registerGlobalReceiver(C2SPopulateDefaultChannelPacket.PACKET_ID, new C2SPopulateDefaultChannelPacket());

    }
}
