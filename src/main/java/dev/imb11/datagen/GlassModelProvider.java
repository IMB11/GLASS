package dev.imb11.datagen;

import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.ProjectorBlock;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricModelProvider;
import net.minecraft.data.models.BlockModelGenerators;
import net.minecraft.data.models.ItemModelGenerators;
import net.minecraft.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.data.models.blockstates.PropertyDispatch;
import net.minecraft.data.models.blockstates.Variant;
import net.minecraft.data.models.blockstates.VariantProperties;
import net.minecraft.data.models.model.ModelTemplates;
import net.minecraft.data.models.model.TextureMapping;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class GlassModelProvider extends FabricModelProvider {
    public GlassModelProvider(FabricDataOutput output) {
        super(output);
    }

    @Override
    public void generateBlockStateModels(BlockModelGenerators generator) {
        createCube(generator, GBlocks.REDSTONE_INFUSED_SAND, Blocks.RED_SAND);
        createCube(generator, GBlocks.POWERABLE_GLASS, Blocks.GLASS);
        createCube(generator, GBlocks.TERMINAL, Blocks.OBSIDIAN);

        ResourceLocation projectorModel = ModelTemplates.CUBE_ALL.create(GBlocks.PROJECTOR, TextureMapping.cube(Blocks.GLASS), generator.modelOutput);
        generator.blockStateOutput.accept(MultiVariantGenerator.multiVariant(GBlocks.PROJECTOR)
                .with(PropertyDispatch.property(ProjectorBlock.POWERED)
                        .select(false, Variant.variant().with(VariantProperties.MODEL, projectorModel))
                        .select(true, Variant.variant().with(VariantProperties.MODEL, projectorModel))));
        generator.skipAutoItemBlock(GBlocks.PROJECTOR);
    }

    @Override
    public void generateItemModels(ItemModelGenerators generator) {
    }

    private static void createCube(BlockModelGenerators generator, Block block, Block texture) {
        ResourceLocation model = ModelTemplates.CUBE_ALL.create(block, TextureMapping.cube(texture), generator.modelOutput);
        generator.blockStateOutput.accept(MultiVariantGenerator.multiVariant(block, Variant.variant().with(VariantProperties.MODEL, model)));
    }
}
