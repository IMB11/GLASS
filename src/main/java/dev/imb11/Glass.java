package dev.imb11;

import dev.imb11.blocks.GBlocks;
import dev.imb11.client.gui.TerminalBlockGUI;
import dev.imb11.items.GItems;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.GNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.world.PersistentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Glass implements ModInitializer {
    public static final String MOD_ID = "glass";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        GBlocks.init();
        GItems.init();
        GNetworking.initialize();
        ChannelManagerPersistence.init();
    }
}
