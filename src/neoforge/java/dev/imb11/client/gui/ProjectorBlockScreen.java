package dev.imb11.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class ProjectorBlockScreen extends ChannelScreen<ProjectorBlockGUI> {
    public ProjectorBlockScreen(ProjectorBlockGUI menu, Inventory inventory, Component title) {
        super(menu, inventory, title, false);
    }
}
