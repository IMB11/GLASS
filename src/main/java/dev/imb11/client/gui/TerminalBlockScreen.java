package dev.imb11.client.gui;

import io.github.cottonmc.cotton.gui.client.CottonInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

public class TerminalBlockScreen extends CottonInventoryScreen<TerminalBlockGUI> {
    public TerminalBlockScreen(TerminalBlockGUI gui, Player player, Component title) {
        super(gui, player, title);
    }

    @Override
    public boolean keyPressed(int ch, int keyCode, int modifiers) {
        return super.keyPressed(ch, keyCode, modifiers);
    }
}