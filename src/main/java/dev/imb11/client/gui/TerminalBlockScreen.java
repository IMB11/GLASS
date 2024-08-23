package dev.imb11.client.gui;

import io.github.cottonmc.cotton.gui.client.CottonInventoryScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

public class TerminalBlockScreen extends CottonInventoryScreen<TerminalBlockGUI> {
    public TerminalBlockScreen(TerminalBlockGUI gui, PlayerEntity player, Text title) {
        super(gui, player, title);
    }
}