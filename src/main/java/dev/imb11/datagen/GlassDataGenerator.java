package dev.imb11.datagen;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

public class GlassDataGenerator implements DataGeneratorEntrypoint {
    @Override
    public void onInitializeDataGenerator(FabricDataGenerator generator) {
        FabricDataGenerator.Pack pack = generator.createPack();
        pack.addProvider(GlassRecipeProvider::new);
        pack.addProvider(GlassBlockLootTableProvider::new);
        pack.addProvider(GlassBlockTagProvider::new);
        pack.addProvider(GlassModelProvider::new);
        pack.addProvider(GlassLanguageProvider::new);
    }
}
