package dev.imb11.blocks;

import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.client.gui.TerminalBlockGUI;
import dev.imb11.client.renderer.block.ProjectorBlockEntityRenderer;
import dev.imb11.client.renderer.block.TerminalBlockEntityRenderer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class GBlocks {
    public static final TerminalBlock TERMINAL = new TerminalBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OBSIDIAN));
    public static final ProjectorBlock PROJECTOR = new ProjectorBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BEACON));

    public static void init() {
        register("terminal", TERMINAL);
        register("projector", PROJECTOR);

        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("glass", "terminal_entity"), TerminalBlockEntity.BLOCK_ENTITY_TYPE);
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("glass", "projector_entity"), ProjectorBlockEntity.BLOCK_ENTITY_TYPE);

        Registry.register(BuiltInRegistries.MENU, ResourceLocation.fromNamespaceAndPath("glass", "terminal_gui"), TerminalBlockGUI.SCREEN_HANDLER_TYPE);
        Registry.register(BuiltInRegistries.MENU, ResourceLocation.fromNamespaceAndPath("glass", "projector_gui"), ProjectorBlockGUI.SCREEN_HANDLER_TYPE);
    }

    public static void initClient() {
        BlockEntityRenderers.register(TerminalBlockEntity.BLOCK_ENTITY_TYPE, TerminalBlockEntityRenderer::new);
        BlockEntityRenderers.register(ProjectorBlockEntity.BLOCK_ENTITY_TYPE, ProjectorBlockEntityRenderer::new);

        BlockRenderLayerMap.INSTANCE.putBlock(PROJECTOR, RenderType.cutout());
    }

    private static <T extends Block> T register(String id, T block) {
        return Registry.register(BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath("glass", id), block);
    }
}
