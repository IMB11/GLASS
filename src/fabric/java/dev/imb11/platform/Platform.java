package dev.imb11.platform;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Player;

public final class Platform {
    private Platform() {
    }

    public static boolean isModLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    public static <T> void openMenu(Player player, ExtendedMenuProvider<T> provider) {
        if (provider != null) {
            player.openMenu(provider);
        }
    }
}
