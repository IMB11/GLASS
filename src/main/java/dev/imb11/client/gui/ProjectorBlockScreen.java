package dev.imb11.client.gui;

import io.github.cottonmc.cotton.gui.client.CottonInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

public class ProjectorBlockScreen extends CottonInventoryScreen<ProjectorBlockGUI> {
    public ProjectorBlockScreen(ProjectorBlockGUI gui, Player player, Component title) {
        super(gui, player, title);
    }
}