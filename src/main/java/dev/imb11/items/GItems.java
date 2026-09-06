package dev.imb11.items;

import dev.imb11.Glass;
import dev.imb11.blocks.GBlocks;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

public class GItems {
    public static final BlockItem TERMINAL = new BlockItem(GBlocks.TERMINAL, new Item.Properties());
    public static final BlockItem PROJECTOR = new BlockItem(GBlocks.PROJECTOR, new Item.Properties());
    public static final BlockItem REDSTONE_INFUSED_SAND = new BlockItem(GBlocks.REDSTONE_INFUSED_SAND, new Item.Properties());
    public static final BlockItem POWERABLE_GLASS = new BlockItem(GBlocks.POWERABLE_GLASS, new Item.Properties());

    public static void init() {
        register("terminal", TERMINAL);
        register("projector", PROJECTOR);
        register("redstone_infused_sand", REDSTONE_INFUSED_SAND);
        register("powerable_glass", POWERABLE_GLASS);
    }

    private static <T extends Item> T register(String id, T item) {
        return Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(Glass.MOD_ID, id), item);
    }
}
