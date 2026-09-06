package dev.imb11.datagen;

import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.ProjectorBlock;
import dev.imb11.blocks.TerminalBlock;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricModelProvider;
import net.minecraft.core.Direction;
import net.minecraft.data.models.BlockModelGenerators;
import net.minecraft.data.models.ItemModelGenerators;
import net.minecraft.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.data.models.blockstates.PropertyDispatch;
import net.minecraft.data.models.blockstates.Variant;
import net.minecraft.data.models.blockstates.VariantProperties;
import net.minecraft.data.models.model.ModelTemplate;
import net.minecraft.data.models.model.ModelTemplates;
import net.minecraft.data.models.model.TextureMapping;
import net.minecraft.data.models.model.TextureSlot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.Optional;

public class GlassModelProvider extends FabricModelProvider {
    public GlassModelProvider(FabricDataOutput output) {
        super(output);
    }

    @Override
    public void generateBlockStateModels(BlockModelGenerators generator) {
        createCube(generator, GBlocks.REDSTONE_INFUSED_SAND, GBlocks.REDSTONE_INFUSED_SAND);
        createCube(generator, GBlocks.POWERABLE_GLASS, GBlocks.POWERABLE_GLASS);

        ResourceLocation terminalModel = new ModelTemplate(Optional.of(ResourceLocation.fromNamespaceAndPath("glass", "block/terminal_camera")), Optional.empty())
                .create(GBlocks.TERMINAL, new TextureMapping(), generator.modelOutput);
        ResourceLocation projectingTerminalModel = new ModelTemplate(Optional.of(terminalModel), Optional.of("_projecting"), TextureSlot.FRONT)
                .create(GBlocks.TERMINAL, new TextureMapping().put(TextureSlot.FRONT,
                        ResourceLocation.fromNamespaceAndPath("glass", "block/terminal_sensor_recording")), generator.modelOutput);
        generator.blockStateOutput.accept(MultiVariantGenerator.multiVariant(GBlocks.TERMINAL)
                .with(PropertyDispatch.property(TerminalBlock.PROJECTING)
                        .select(false, Variant.variant().with(VariantProperties.MODEL, terminalModel))
                        .select(true, Variant.variant().with(VariantProperties.MODEL, projectingTerminalModel)))
                .with(PropertyDispatch.property(TerminalBlock.FACING)
                        .select(Direction.SOUTH, Variant.variant())
                        .select(Direction.NORTH, Variant.variant().with(VariantProperties.Y_ROT, VariantProperties.Rotation.R180))
                        .select(Direction.WEST, Variant.variant().with(VariantProperties.Y_ROT, VariantProperties.Rotation.R90))
                        .select(Direction.EAST, Variant.variant().with(VariantProperties.Y_ROT, VariantProperties.Rotation.R270))
                        .select(Direction.UP, Variant.variant().with(VariantProperties.X_ROT, VariantProperties.Rotation.R90))
                        .select(Direction.DOWN, Variant.variant().with(VariantProperties.X_ROT, VariantProperties.Rotation.R270).with(VariantProperties.Y_ROT, VariantProperties.Rotation.R180))));

        ResourceLocation projectorModel = ModelTemplates.CUBE_ALL.create(GBlocks.PROJECTOR, TextureMapping.cube(GBlocks.POWERABLE_GLASS), generator.modelOutput);
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
