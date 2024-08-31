package dev.imb11.sync;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class GNetworking {
    private static Identifier id(String id) {
        return Identifier.of("glass", id);
    }

    public static void initialize() {

    }
}
