package dev.imb11.items;

import dev.imb11.Glass;
import dev.imb11.blocks.GBlocks;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroupEntries;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

public class GItems {
    public static final BlockItem TERMINAL = new BlockItem(GBlocks.TERMINAL, new Item.Properties());
    public static final BlockItem PROJECTOR = new BlockItem(GBlocks.PROJECTOR, new Item.Properties());

    public static void init() {
        register("terminal", TERMINAL);
        register("projector", PROJECTOR);

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.REDSTONE_BLOCKS).register(entries -> {
            entries.accept(TERMINAL);
            entries.accept(PROJECTOR);
        });
    }

    public static void initClient() {

    }

    private static <T extends Item> T register(String id, T item) {
        return Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(Glass.MOD_ID, id), item);
    }
}
