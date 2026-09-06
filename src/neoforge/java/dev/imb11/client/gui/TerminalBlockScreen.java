package dev.imb11.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class TerminalBlockScreen extends ChannelScreen<TerminalBlockGUI> {
    public TerminalBlockScreen(TerminalBlockGUI menu, Inventory inventory, Component title) {
        super(menu, inventory, title, true);
    }
}
