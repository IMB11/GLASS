package dev.imb11.registry;

import dev.imb11.Glass;
import dev.imb11.blocks.TestProjectorBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class GBlocks {
    public static final Block TEST_PROJECTOR_BLOCK = register("test_projector", new TestProjectorBlock(AbstractBlock.Settings.copy(Blocks.GLASS)));

    public static void initialize() {}
    private static <T extends Block> T register(String id, T block) {
        return Registry.register(Registries.BLOCK, new Identifier(Glass.MOD_ID, id), block);
    }
}
