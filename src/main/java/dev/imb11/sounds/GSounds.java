package dev.imb11.sounds;

import dev.imb11.Glass;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

public final class GSounds {
    private static final ResourceLocation PROJECTION_PANEL_ACTIVATE_ID =
            ResourceLocation.fromNamespaceAndPath(Glass.MOD_ID, "projection.panel_activate");
    public static final SoundEvent PROJECTION_PANEL_ACTIVATE =
            SoundEvent.createVariableRangeEvent(PROJECTION_PANEL_ACTIVATE_ID);
    private static final ResourceLocation PROJECTION_PANEL_DEACTIVATE_ID =
            ResourceLocation.fromNamespaceAndPath(Glass.MOD_ID, "projection.panel_deactivate");
    public static final SoundEvent PROJECTION_PANEL_DEACTIVATE =
            SoundEvent.createVariableRangeEvent(PROJECTION_PANEL_DEACTIVATE_ID);

    private GSounds() {
    }

    public static void init() {
        Registry.register(BuiltInRegistries.SOUND_EVENT, PROJECTION_PANEL_ACTIVATE_ID, PROJECTION_PANEL_ACTIVATE);
        Registry.register(BuiltInRegistries.SOUND_EVENT, PROJECTION_PANEL_DEACTIVATE_ID, PROJECTION_PANEL_DEACTIVATE);
    }
}
