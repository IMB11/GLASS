package dev.imb11.client.gui;

import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;

public final class TerminalBlockGUI extends ChannelMenu {
    public static final MenuType<TerminalBlockGUI> SCREEN_HANDLER_TYPE = IMenuTypeExtension.create((id, inventory, buffer) ->
            new TerminalBlockGUI(id, inventory, TerminalBlockEntity.ScreenHandlerData.CODEC.decode(
                    new RegistryFriendlyByteBuf(buffer, inventory.player.registryAccess()))));

    public TerminalBlockGUI(int id, Inventory inventory, TerminalBlockEntity.ScreenHandlerData data) {
        super(SCREEN_HANDLER_TYPE, id, inventory, data.pos(), data.channelManagerNbt(), GBlocks.TERMINAL);
    }
}
