package dev.imb11.platform;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;

public interface ExtendedMenuProvider<T> extends MenuProvider {
    T getScreenOpeningData(ServerPlayer player);

    StreamCodec<RegistryFriendlyByteBuf, T> getScreenOpeningCodec();
}
