package dev.imb11.datagen;

import dev.imb11.blocks.GBlocks;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.core.HolderLookup;

import java.util.concurrent.CompletableFuture;

public class GlassLanguageProvider extends FabricLanguageProvider {
    public GlassLanguageProvider(FabricDataOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
        super(output, "en_us", registriesFuture);
    }

    @Override
    public void generateTranslations(HolderLookup.Provider registries, TranslationBuilder translations) {
        translations.add(GBlocks.REDSTONE_INFUSED_SAND, "Redstone Infused Sand");
        translations.add(GBlocks.POWERABLE_GLASS, "Powerable Glass");
        translations.add(GBlocks.PROJECTOR, "G.L.A.S.S Projector");
        translations.add(GBlocks.TERMINAL, "G.L.A.S.S Terminal");
        translations.add("subtitles.glass.projection.panel_activate", "Projection panel activates");
        translations.add("subtitles.glass.projection.panel_deactivate", "Projection panel deactivates");
    }
}
