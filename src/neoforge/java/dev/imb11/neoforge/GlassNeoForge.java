package dev.imb11.neoforge;

import dev.imb11.Glass;
import dev.imb11.blocks.GBlocks;
import dev.imb11.items.GItems;
import dev.imb11.platform.PlatformNetworking;
import dev.imb11.sounds.GSounds;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.GNetworking;
import dev.imb11.sync.remote.RemoteSceneServerManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(Glass.MOD_ID)
public final class GlassNeoForge {
    public GlassNeoForge(IEventBus modBus) {
        GNetworking.initialize();
        modBus.addListener(GlassNeoForge::register);
        modBus.addListener(GlassNeoForge::creativeTab);
        modBus.addListener(PlatformNetworking::registerPayloads);
        NeoForge.EVENT_BUS.addListener(GlassNeoForge::serverTick);
        NeoForge.EVENT_BUS.addListener(GlassNeoForge::playerJoined);
        NeoForge.EVENT_BUS.addListener(GlassNeoForge::playerLeft);
        NeoForge.EVENT_BUS.addListener(GlassNeoForge::playerChangedDimension);
        NeoForge.EVENT_BUS.addListener(GlassNeoForge::levelLoaded);
        NeoForge.EVENT_BUS.addListener(GlassNeoForge::levelUnloaded);
        NeoForge.EVENT_BUS.addListener(GlassNeoForge::serverStopping);
    }

    private static void register(RegisterEvent event) {
        if (event.getRegistryKey().equals(Registries.BLOCK)) {
            GBlocks.registerBlocks();
        } else if (event.getRegistryKey().equals(Registries.ITEM)) {
            GItems.init();
        } else if (event.getRegistryKey().equals(Registries.BLOCK_ENTITY_TYPE)) {
            GBlocks.registerBlockEntities();
        } else if (event.getRegistryKey().equals(Registries.MENU)) {
            GBlocks.registerMenus();
        } else if (event.getRegistryKey().equals(Registries.SOUND_EVENT)) {
            GSounds.init();
        }
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.REDSTONE_BLOCKS)) {
            event.accept(GItems.TERMINAL);
            event.accept(GItems.PROJECTOR);
            event.accept(GItems.REDSTONE_INFUSED_SAND);
            event.accept(GItems.POWERABLE_GLASS);
        }
    }

    private static void serverTick(ServerTickEvent.Post event) {
        RemoteSceneServerManager.tick(event.getServer());
    }

    private static void playerJoined(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            GNetworking.sendSnapshot(player);
        }
    }

    private static void playerLeft(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RemoteSceneServerManager.removePlayer(player.server, player, false);
        }
    }

    private static void playerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RemoteSceneServerManager.removePlayer(player.server, player, true);
            GNetworking.sendSnapshot(player);
        }
    }

    private static void levelLoaded(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ChannelManagerPersistence.onLevelLoad(level);
        }
    }

    private static void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            RemoteSceneServerManager.unloadWorld(level.getServer(), level);
        }
    }

    private static void serverStopping(ServerStoppingEvent event) {
        RemoteSceneServerManager.stop(event.getServer());
    }
}
