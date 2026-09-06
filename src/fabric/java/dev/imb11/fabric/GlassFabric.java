package dev.imb11.fabric;

import dev.imb11.blocks.GBlocks;
import dev.imb11.items.GItems;
import dev.imb11.sounds.GSounds;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.GNetworking;
import dev.imb11.sync.remote.RemoteSceneServerManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.item.CreativeModeTabs;

public final class GlassFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        GBlocks.registerBlocks();
        GBlocks.registerBlockEntities();
        GBlocks.registerMenus();
        GItems.init();
        GSounds.init();
        GNetworking.initialize();

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.REDSTONE_BLOCKS).register(entries -> {
            entries.accept(GItems.TERMINAL);
            entries.accept(GItems.PROJECTOR);
            entries.accept(GItems.REDSTONE_INFUSED_SAND);
            entries.accept(GItems.POWERABLE_GLASS);
        });
        ServerTickEvents.END_SERVER_TICK.register(RemoteSceneServerManager::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> GNetworking.sendSnapshot(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> RemoteSceneServerManager.removePlayer(server, handler.getPlayer(), false));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            RemoteSceneServerManager.removePlayer(destination.getServer(), player, true);
            GNetworking.sendSnapshot(player);
        });
        ServerWorldEvents.LOAD.register((server, level) -> ChannelManagerPersistence.onLevelLoad(level));
        ServerWorldEvents.UNLOAD.register(RemoteSceneServerManager::unloadWorld);
        ServerLifecycleEvents.SERVER_STOPPING.register(RemoteSceneServerManager::stop);
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register(ChannelManagerPersistence::onBlockEntityLoad);
    }
}
