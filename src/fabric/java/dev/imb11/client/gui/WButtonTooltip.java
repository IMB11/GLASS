package dev.imb11.client.gui;

import io.github.cottonmc.cotton.gui.widget.TooltipBuilder;
import io.github.cottonmc.cotton.gui.widget.WButton;
import net.minecraft.network.chat.Component;

public class WButtonTooltip extends WButton {
    public Component[] tooltips;

    public void setTooltip(Component... tooltip) {
        tooltips = tooltip;
    }

    @Override
    public void addTooltip(TooltipBuilder tooltip) {
        if(tooltips == null) return;
        tooltip.add(this.tooltips);
    }
}