package dev.imb11.datagen;

import dev.imb11.blocks.GBlocks;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricBlockLootTableProvider;
import net.minecraft.core.HolderLookup;

import java.util.concurrent.CompletableFuture;

public class GlassBlockLootTableProvider extends FabricBlockLootTableProvider {
    public GlassBlockLootTableProvider(FabricDataOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
        super(output, registriesFuture);
    }

    @Override
    public void generate() {
        dropSelf(GBlocks.REDSTONE_INFUSED_SAND);
        dropWhenSilkTouch(GBlocks.POWERABLE_GLASS);
        dropSelf(GBlocks.PROJECTOR);
        dropSelf(GBlocks.TERMINAL);
    }
}
