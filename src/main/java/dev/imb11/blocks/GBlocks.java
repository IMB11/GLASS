package dev.imb11.blocks;

import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.client.gui.TerminalBlockGUI;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ColorRGBA;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ColoredFallingBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class GBlocks {
    public static final TerminalBlock TERMINAL = new TerminalBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OBSIDIAN));
    public static final ProjectorBlock PROJECTOR = new ProjectorBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BEACON));
    public static final ColoredFallingBlock REDSTONE_INFUSED_SAND = new ColoredFallingBlock(new ColorRGBA(0xCF2929), BlockBehaviour.Properties.ofFullCopy(Blocks.SAND));
    public static final PowerableGlassBlock POWERABLE_GLASS = new PowerableGlassBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.GLASS));

    public static void registerBlocks() {
        register("terminal", TERMINAL);
        register("projector", PROJECTOR);
        register("redstone_infused_sand", REDSTONE_INFUSED_SAND);
        register("powerable_glass", POWERABLE_GLASS);
    }

    public static void registerBlockEntities() {
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("glass", "terminal_entity"), TerminalBlockEntity.BLOCK_ENTITY_TYPE);
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("glass", "projector_entity"), ProjectorBlockEntity.BLOCK_ENTITY_TYPE);
    }

    public static void registerMenus() {
        Registry.register(BuiltInRegistries.MENU, ResourceLocation.fromNamespaceAndPath("glass", "terminal_gui"), TerminalBlockGUI.SCREEN_HANDLER_TYPE);
        Registry.register(BuiltInRegistries.MENU, ResourceLocation.fromNamespaceAndPath("glass", "projector_gui"), ProjectorBlockGUI.SCREEN_HANDLER_TYPE);
    }

    private static <T extends Block> T register(String id, T block) {
        return Registry.register(BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath("glass", id), block);
    }
}
