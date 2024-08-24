package dev.imb11;

import dev.imb11.blocks.GBlocks;
import dev.imb11.items.GItems;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.GPackets;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
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
        GPackets.initialize();

        ServerWorldEvents.LOAD.register((server, world) -> {
            world.getPersistentStateManager().getOrCreate(ChannelManagerPersistence::gather, ChannelManagerPersistence::new, "glass_channels");
            LOGGER.info("Loaded ChannelManagerPersistence for: " + world.getDimensionKey().getValue() + " at " + world);
        });
    }
}
