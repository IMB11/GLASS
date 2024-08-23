package dev.imb11.items;

import dev.imb11.Glass;
import dev.imb11.blocks.GBlocks;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroupEntries;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class GItems {
    public static final BlockItem TERMINAL = new BlockItem(GBlocks.TERMINAL, new FabricItemSettings());
    public static final BlockItem PROJECTOR = new BlockItem(GBlocks.PROJECTOR, new FabricItemSettings());
//    public static final BlockItem PROJECTION_PANEL = new BlockItem(GBlocks.PROJECTION_PANEL, new FabricItemSettings());

    public static void init() {
        register("terminal", TERMINAL);
        register("projector", PROJECTOR);
//        register("projection_panel", PROJECTION_PANEL);

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(entries -> {
            entries.add(TERMINAL);
            entries.add(PROJECTOR);
        });
    }

    public static void initClient() {

    }

    private static <T extends Item> T register(String id, T item) {
        return Registry.register(Registries.ITEM, new Identifier(Glass.MOD_ID, id), item);
    }
}