package dev.imb11;

import dev.imb11.registry.GBlockEntities;
import dev.imb11.registry.GBlocks;
import net.fabricmc.api.ModInitializer;

public class Glass implements ModInitializer {
    public static final String MOD_ID = "glassier";

    @Override
    public void onInitialize() {
        GBlocks.initialize();
        GBlockEntities.initialize();
    }
}
