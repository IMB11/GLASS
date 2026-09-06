package dev.imb11.platform;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

public final class Platform {
    private Platform() {
    }

    public static boolean isModLoaded(String id) {
        return ModList.get().isLoaded(id);
    }

    public static <T> void openMenu(Player player, ExtendedMenuProvider<T> provider) {
        if (player instanceof ServerPlayer serverPlayer && provider != null) {
            serverPlayer.openMenu(provider, buffer -> provider.getScreenOpeningCodec().encode(
                    new RegistryFriendlyByteBuf(buffer, serverPlayer.registryAccess()),
                    provider.getScreenOpeningData(serverPlayer)
            ));
        }
    }
}
