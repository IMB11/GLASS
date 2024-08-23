package dev.imb11.blocks;

import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.client.renderer.block.ProjectorBlockEntityRenderer;
import dev.imb11.client.renderer.block.TerminalBlockEntityRenderer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class GBlocks {
    public static final TerminalBlock TERMINAL = new TerminalBlock(AbstractBlock.Settings.copy(Blocks.OBSIDIAN));
    public static final ProjectorBlock PROJECTOR = new ProjectorBlock(AbstractBlock.Settings.copy(Blocks.BEACON));
//    public static final ProjectionBlock PROJECTION_PANEL = new ProjectionBlock(AbstractBlock.Settings.copy(Blocks.GLASS));

    public static void init() {
        register("terminal", TERMINAL);
        register("projector", PROJECTOR);
//        register("projection_panel", PROJECTION_PANEL);

        Registry.register(Registries.BLOCK_ENTITY_TYPE, new Identifier("glass", "terminal_entity"), TerminalBlockEntity.BLOCK_ENTITY_TYPE);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, new Identifier("glass", "projector_entity"), ProjectorBlockEntity.BLOCK_ENTITY_TYPE);
//        Registry.register(Registries.BLOCK_ENTITY_TYPE, new Identifier("glass", "projection_entity"), ProjectionBlockBase.BLOCK_ENTITY_TYPE);
    }

    public static void initClient() {
        BlockEntityRendererFactories.register(TerminalBlockEntity.BLOCK_ENTITY_TYPE, TerminalBlockEntityRenderer::new);
        BlockEntityRendererFactories.register(ProjectorBlockEntity.BLOCK_ENTITY_TYPE, ProjectorBlockEntityRenderer::new);

        BlockRenderLayerMap.INSTANCE.putBlock(PROJECTOR, RenderLayer.getCutout());
    }

    private static <T extends Block> T register(String id, T block) {
        return Registry.register(Registries.BLOCK, new Identifier("glass", id), block);
    }
}