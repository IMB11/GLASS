package dev.imb11.platform;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public interface ExtendedMenuProvider<T> extends ExtendedScreenHandlerFactory<T> {
    StreamCodec<RegistryFriendlyByteBuf, T> getScreenOpeningCodec();
}
