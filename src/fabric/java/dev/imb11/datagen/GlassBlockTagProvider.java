package dev.imb11.datagen;

import dev.imb11.blocks.GBlocks;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.tags.BlockTags;

import java.util.concurrent.CompletableFuture;

public class GlassBlockTagProvider extends FabricTagProvider.BlockTagProvider {
    public GlassBlockTagProvider(FabricDataOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
        super(output, registriesFuture);
    }

    @Override
    protected void addTags(HolderLookup.Provider registries) {
        tag(BlockTags.MINEABLE_WITH_SHOVEL).add(GBlocks.REDSTONE_INFUSED_SAND.builtInRegistryHolder().key());
        tag(BlockTags.MINEABLE_WITH_PICKAXE).add(GBlocks.PROJECTOR.builtInRegistryHolder().key(), GBlocks.TERMINAL.builtInRegistryHolder().key());
        tag(BlockTags.NEEDS_DIAMOND_TOOL).add(GBlocks.TERMINAL.builtInRegistryHolder().key());
    }
}
