package dev.imb11.client;

import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.client.gui.ProjectorBlockScreen;
import dev.imb11.client.gui.TerminalBlockGUI;
import dev.imb11.client.gui.TerminalBlockScreen;
import dev.imb11.blocks.GBlocks;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;

public class GlassClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        GBlocks.initClient();

        MenuScreens.<TerminalBlockGUI, TerminalBlockScreen>register(TerminalBlockGUI.SCREEN_HANDLER_TYPE, (gui, inventory, title) -> new TerminalBlockScreen(gui, inventory.player, title));
        MenuScreens.<ProjectorBlockGUI, ProjectorBlockScreen>register(ProjectorBlockGUI.SCREEN_HANDLER_TYPE, (gui, inventory, title) -> new ProjectorBlockScreen(gui, inventory.player, title));
    }
}
