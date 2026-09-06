package dev.imb11.datagen;

import dev.imb11.items.GItems;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.concurrent.CompletableFuture;

public class GlassRecipeProvider extends FabricRecipeProvider {
    public GlassRecipeProvider(FabricDataOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
        super(output, registriesFuture);
    }

    @Override
    public void buildRecipes(RecipeOutput output) {
        ShapelessRecipeBuilder.shapeless(RecipeCategory.BUILDING_BLOCKS, GItems.REDSTONE_INFUSED_SAND)
                .requires(Items.REDSTONE)
                .requires(Items.SAND)
                .unlockedBy("has_ingredient", has(Items.REDSTONE))
                .save(output);

        SimpleCookingRecipeBuilder.smelting(Ingredient.of(GItems.REDSTONE_INFUSED_SAND), RecipeCategory.BUILDING_BLOCKS, GItems.POWERABLE_GLASS, 0.1F, 200)
                .unlockedBy("has_ingredient", has(GItems.REDSTONE_INFUSED_SAND))
                .save(output);

        ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, GItems.PROJECTOR)
                .pattern("GQG")
                .pattern("GRG")
                .pattern("GDG")
                .define('G', Items.GLASS)
                .define('Q', Items.QUARTZ)
                .define('R', Items.REDSTONE)
                .define('D', Items.DIAMOND)
                .unlockedBy("has_ingredient", has(Items.QUARTZ))
                .save(output);

        ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, GItems.TERMINAL)
                .pattern("OBO")
                .pattern("ODO")
                .pattern("OOO")
                .define('O', Items.OBSIDIAN)
                .define('B', Items.OBSERVER)
                .define('D', Items.DIAMOND)
                .unlockedBy("has_ingredient", has(Items.OBSERVER))
                .save(output);
    }
}
