package dev.imb11.registry;

import dev.imb11.Glass;
import dev.imb11.blocks.TestProjectorBlock;
import dev.imb11.blocks.TestProjectorBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.datafixer.TypeReferences;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

public class GBlockEntities {
    public static final BlockEntityType<TestProjectorBlockEntity> TEST_PROJECTOR_BLOCK_ENTITY_TYPE = register("test_projector", FabricBlockEntityTypeBuilder.create(TestProjectorBlockEntity::new, GBlocks.TEST_PROJECTOR_BLOCK).build(Util.getChoiceType(TypeReferences.BLOCK_ENTITY, "test_projector")));

    public static void initialize() {}

    private static <T extends BlockEntityType<?>> T register(String id, T type) {
        return Registry.register(Registries.BLOCK_ENTITY_TYPE, new Identifier(Glass.MOD_ID, id), type);
    }
}
