package dev.imb11.client.gui;

import io.github.cottonmc.cotton.gui.client.CottonInventoryScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

public class ProjectorBlockScreen extends CottonInventoryScreen<ProjectorBlockGUI> {
    public ProjectorBlockScreen(ProjectorBlockGUI gui, PlayerEntity player, Text title) {
        super(gui, player, title);
    }
}