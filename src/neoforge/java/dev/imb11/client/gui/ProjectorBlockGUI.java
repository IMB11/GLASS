package dev.imb11.client.gui;

import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;

public final class ProjectorBlockGUI extends ChannelMenu {
    public static final MenuType<ProjectorBlockGUI> SCREEN_HANDLER_TYPE = IMenuTypeExtension.create((id, inventory, buffer) ->
            new ProjectorBlockGUI(id, inventory, ProjectorBlockEntity.ScreenHandlerData.CODEC.decode(
                    new RegistryFriendlyByteBuf(buffer, inventory.player.registryAccess()))));
    public final String initialChannel;

    public ProjectorBlockGUI(int id, Inventory inventory, ProjectorBlockEntity.ScreenHandlerData data) {
        super(SCREEN_HANDLER_TYPE, id, inventory, data.pos(), data.compound(), GBlocks.PROJECTOR);
        initialChannel = data.channel();
    }
}
